// OmniCode's native Windows workspace-write boundary.
//
// The JVM deliberately does not attempt to emulate AppContainer. This small, signed
// broker is the only process allowed to create an AppContainer child. It grants the
// per-run AppContainer SID access to the project for the lifetime of the child, then
// restores every original DACL before returning. Network capabilities are intentionally
// empty. A failed grant or restore is a hard failure; the caller must never downgrade.

#include <windows.h>
#include <aclapi.h>
#include <appmodel.h>
#include <knownfolders.h>
#include <shellapi.h>
#include <shlobj.h>
#include <sddl.h>
#include <userenv.h>

#include <algorithm>
#include <cstdint>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <unordered_set>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr size_t kMaxArguments = 128;
constexpr size_t kMaxArgumentCharacters = 64 * 1024;
constexpr size_t kMaxAclEntries = 100000;
constexpr size_t kMaxAclBytes = 64 * 1024 * 1024;

struct Profile {
    std::wstring name;
    PSID sid = nullptr;

    Profile() = default;

    ~Profile() {
        if (sid != nullptr) {
            LocalFree(sid);
        }
        if (!name.empty()) {
            DeleteAppContainerProfile(name.c_str());
        }
    }

    Profile(const Profile&) = delete;
    Profile& operator=(const Profile&) = delete;
};

struct AclSnapshot {
    std::wstring path;
    std::vector<BYTE> descriptor;
    bool directory = false;

    PACL dacl() const {
        BOOL present = FALSE;
        BOOL defaulted = FALSE;
        PACL value = nullptr;
        if (descriptor.empty() || !GetSecurityDescriptorDacl(
                reinterpret_cast<PSECURITY_DESCRIPTOR>(const_cast<BYTE*>(descriptor.data())),
                &present,
                &value,
                &defaulted)) {
            return nullptr;
        }
        return present ? value : nullptr;
    }
};

struct AclTransaction {
    std::wstring root;
    PSID sid = nullptr;
    std::vector<AclSnapshot> originals;
    std::unordered_set<std::wstring> originalPaths;
    std::wstring journalPath;
    bool recoveryMode = false;
    bool ownsSid = false;
    bool active = false;

    ~AclTransaction() {
        if (active) restore();
        if (ownsSid && sid != nullptr) LocalFree(sid);
    }

    static std::wstring key(const std::wstring& path) {
        std::wstring value = path;
        std::transform(value.begin(), value.end(), value.begin(), towlower);
        return value;
    }

    bool begin(const std::wstring& workspace, PSID appContainerSid, bool readOnly, std::wstring& error) {
        root = workspace;
        sid = appContainerSid;
        if (!captureTree(error)) return false;
        if (!writeJournal(error)) return false;
        active = true;
        for (const auto& snapshot : originals) {
            if (!grant(snapshot, readOnly, error)) {
                restore();
                return false;
            }
        }
        return true;
    }

    bool restore() {
        bool ok = true;
        // New files inherit the AppContainer ACE. Remove it before restoring the original
        // objects, otherwise a successful command could leave a persistent principal behind.
        ok = removeGrantFromNewObjects() && ok;
        for (auto it = originals.rbegin(); it != originals.rend(); ++it) {
            if (!restoreSnapshot(*it)) ok = false;
        }
        if (ok && !journalPath.empty()) DeleteFileW(journalPath.c_str());
        active = false;
        return ok;
    }

    static void recoverOrphanedJournals() {
        const auto directory = recoveryDirectory();
        std::error_code ec;
        if (!fs::is_directory(directory, ec)) return;
        for (const auto& entry : fs::directory_iterator(directory, ec)) {
            if (ec || !entry.is_regular_file(ec) || entry.path().extension() != L".txn") continue;
            AclTransaction transaction;
            if (transaction.readJournal(entry.path().wstring())) {
                transaction.recoveryMode = true;
                transaction.active = true;
                if (transaction.restore()) {
                    DeleteFileW(entry.path().wstring().c_str());
                }
            }
        }
    }

private:
    static fs::path recoveryDirectory() {
        wchar_t buffer[32768]{};
        const DWORD length = GetTempPathW(static_cast<DWORD>(std::size(buffer)), buffer);
        if (length == 0 || length >= std::size(buffer)) return fs::path();
        return fs::path(std::wstring(buffer, length)) / L"OmniCode" / L"appcontainer-recovery";
    }

    static bool writeString(std::ofstream& stream, const std::wstring& value) {
        if (value.size() > 32767) return false;
        const uint32_t size = static_cast<uint32_t>(value.size());
        stream.write(reinterpret_cast<const char*>(&size), sizeof(size));
        stream.write(reinterpret_cast<const char*>(value.data()), static_cast<std::streamsize>(value.size() * sizeof(wchar_t)));
        return stream.good();
    }

    static bool readString(std::ifstream& stream, std::wstring& value) {
        uint32_t size = 0;
        stream.read(reinterpret_cast<char*>(&size), sizeof(size));
        if (!stream.good() || size > 32767) return false;
        value.resize(size);
        stream.read(reinterpret_cast<char*>(value.data()), static_cast<std::streamsize>(size * sizeof(wchar_t)));
        return stream.good();
    }

    bool writeJournal(std::wstring& error) {
        const auto directory = recoveryDirectory();
        if (directory.empty()) {
            error = L"cannot locate the temporary recovery directory";
            return false;
        }
        std::error_code ec;
        fs::create_directories(directory, ec);
        if (ec) {
            error = L"cannot create the temporary recovery directory";
            return false;
        }
        journalPath = (directory / (L"txn-" + std::to_wstring(GetCurrentProcessId()) + L"-" +
            std::to_wstring(GetTickCount64()) + L".txn")).wstring();
        std::ofstream stream(fs::path(journalPath), std::ios::binary | std::ios::trunc);
        if (!stream.good()) {
            error = L"cannot create the ACL recovery journal";
            return false;
        }
        const uint32_t version = 1;
        stream.write(reinterpret_cast<const char*>(&version), sizeof(version));
        if (!writeString(stream, root)) return false;
        LPWSTR sidString = nullptr;
        if (!ConvertSidToStringSidW(sid, &sidString)) return false;
        const std::wstring sidValue(sidString);
        LocalFree(sidString);
        if (!writeString(stream, sidValue)) return false;
        if (originals.size() > kMaxAclEntries) return false;
        const uint32_t count = static_cast<uint32_t>(originals.size());
        stream.write(reinterpret_cast<const char*>(&count), sizeof(count));
        for (const auto& snapshot : originals) {
            if (!writeString(stream, snapshot.path) || snapshot.descriptor.size() > 8 * 1024 * 1024) return false;
            const uint32_t descriptorSize = static_cast<uint32_t>(snapshot.descriptor.size());
            stream.write(reinterpret_cast<const char*>(&snapshot.directory), sizeof(snapshot.directory));
            stream.write(reinterpret_cast<const char*>(&descriptorSize), sizeof(descriptorSize));
            stream.write(reinterpret_cast<const char*>(snapshot.descriptor.data()), descriptorSize);
            if (!stream.good()) return false;
        }
        stream.flush();
        return stream.good();
    }

    bool readJournal(const std::wstring& path) {
        std::ifstream stream(fs::path(path), std::ios::binary);
        if (!stream.good()) return false;
        uint32_t version = 0;
        stream.read(reinterpret_cast<char*>(&version), sizeof(version));
        if (version != 1 || !readString(stream, root)) return false;
        std::wstring sidString;
        if (!readString(stream, sidString)) return false;
        if (!ConvertStringSidToSidW(sidString.c_str(), &sid)) return false;
        ownsSid = true;
        uint32_t count = 0;
        stream.read(reinterpret_cast<char*>(&count), sizeof(count));
        if (!stream.good() || count > kMaxAclEntries) return false;
        originals.clear();
        originalPaths.clear();
        size_t totalDescriptorBytes = 0;
        for (uint32_t index = 0; index < count; ++index) {
            AclSnapshot snapshot;
            uint32_t descriptorSize = 0;
            if (!readString(stream, snapshot.path)) return false;
            stream.read(reinterpret_cast<char*>(&snapshot.directory), sizeof(snapshot.directory));
            stream.read(reinterpret_cast<char*>(&descriptorSize), sizeof(descriptorSize));
            if (!stream.good() || descriptorSize == 0 || descriptorSize > 8 * 1024 * 1024) return false;
            totalDescriptorBytes += descriptorSize;
            if (totalDescriptorBytes > kMaxAclBytes) return false;
            snapshot.descriptor.resize(descriptorSize);
            stream.read(reinterpret_cast<char*>(snapshot.descriptor.data()), descriptorSize);
            if (!stream.good()) return false;
            originalPaths.insert(key(snapshot.path));
            originals.push_back(std::move(snapshot));
        }
        journalPath = path;
        return true;
    }

    bool captureTree(std::wstring& error) {
        std::error_code ec;
        fs::path rootPath(root);
        if (!fs::exists(rootPath, ec) || !fs::is_directory(rootPath, ec)) {
            error = L"workspace is not a directory";
            return false;
        }
        const auto attributes = GetFileAttributesW(root.c_str());
        if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            error = L"workspace root must not be a reparse point";
            return false;
        }

        originals.clear();
        originalPaths.clear();
        fs::recursive_directory_iterator iterator(rootPath, fs::directory_options::skip_permission_denied, ec);
        if (ec) {
            error = L"cannot enumerate workspace";
            return false;
        }
        auto capture = [&](const fs::path& path, bool directory) -> bool {
            if (originals.size() >= kMaxAclEntries) {
                error = L"workspace contains too many filesystem entries for a bounded ACL transaction";
                return false;
            }
            const auto wide = path.wstring();
            const auto fileAttributes = GetFileAttributesW(wide.c_str());
            if (fileAttributes == INVALID_FILE_ATTRIBUTES ||
                (fileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                error = L"workspace contains a symlink or reparse point; refusing the ACL bridge";
                return false;
            }
            PSECURITY_DESCRIPTOR descriptor = nullptr;
            PACL ignoredDacl = nullptr;
            PSID ignoredOwner = nullptr;
            PSID ignoredGroup = nullptr;
            const DWORD result = GetNamedSecurityInfoW(
                const_cast<LPWSTR>(wide.c_str()),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &ignoredOwner,
                &ignoredGroup,
                &ignoredDacl,
                nullptr,
                &descriptor);
            if (result != ERROR_SUCCESS || descriptor == nullptr) {
                if (descriptor != nullptr) LocalFree(descriptor);
                error = L"cannot read a workspace security descriptor";
                return false;
            }
            const DWORD length = GetSecurityDescriptorLength(descriptor);
            AclSnapshot snapshot;
            snapshot.path = wide;
            snapshot.directory = directory;
            snapshot.descriptor.assign(
                reinterpret_cast<const BYTE*>(descriptor),
                reinterpret_cast<const BYTE*>(descriptor) + length);
            LocalFree(descriptor);
            size_t totalBytes = snapshot.descriptor.size();
            for (const auto& existing : originals) totalBytes += existing.descriptor.size();
            if (totalBytes > kMaxAclBytes) {
                error = L"workspace ACL metadata exceeds the bounded transaction size";
                return false;
            }
            originalPaths.insert(key(wide));
            originals.push_back(std::move(snapshot));
            return true;
        };

        if (!capture(rootPath, true)) return false;
        for (; iterator != fs::recursive_directory_iterator(); iterator.increment(ec)) {
            if (ec) {
                error = L"workspace enumeration failed";
                return false;
            }
            const fs::path path = iterator->path();
            const auto attributesForEntry = GetFileAttributesW(path.wstring().c_str());
            if (attributesForEntry == INVALID_FILE_ATTRIBUTES ||
                (attributesForEntry & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                error = L"workspace contains a symlink or reparse point; refusing the ACL bridge";
                return false;
            }
            const bool directory = iterator->is_directory(ec);
            if (ec) {
                error = L"workspace entry type could not be determined";
                return false;
            }
            if (!capture(path, directory)) return false;
        }
        return true;
    }

    bool grant(const AclSnapshot& snapshot, bool readOnly, std::wstring& error) {
        EXPLICIT_ACCESSW entry{};
        entry.grfAccessPermissions = readOnly
            ? GENERIC_READ
            : (GENERIC_READ | GENERIC_WRITE | DELETE | FILE_DELETE_CHILD);
        entry.grfAccessMode = GRANT_ACCESS;
        entry.grfInheritance = snapshot.directory ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : NO_INHERITANCE;
        entry.Trustee.TrusteeForm = TRUSTEE_IS_SID;
        entry.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
        entry.Trustee.ptstrName = reinterpret_cast<LPWSTR>(sid);

        PACL updated = nullptr;
        const DWORD result = SetEntriesInAclW(1, &entry, snapshot.dacl(), &updated);
        if (result != ERROR_SUCCESS || updated == nullptr) {
            error = L"cannot build the AppContainer workspace ACL";
            if (updated != nullptr) LocalFree(updated);
            return false;
        }
        const DWORD setResult = SetNamedSecurityInfoW(
            const_cast<LPWSTR>(snapshot.path.c_str()),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION,
            nullptr,
            nullptr,
            updated,
            nullptr);
        LocalFree(updated);
        if (setResult != ERROR_SUCCESS) {
            error = L"cannot apply the AppContainer workspace ACL";
            return false;
        }
        return true;
    }

    bool restoreSnapshot(const AclSnapshot& snapshot) {
        if (GetFileAttributesW(snapshot.path.c_str()) == INVALID_FILE_ATTRIBUTES) {
            // Deletion is an allowed workspace mutation. There is no descriptor left to
            // restore, and treating this as a cleanup failure would make every valid delete
            // look like an ACL leak.
            return true;
        }
        if (recoveryMode && !hasSidAce(snapshot.path)) return true;
        PSECURITY_DESCRIPTOR descriptor = reinterpret_cast<PSECURITY_DESCRIPTOR>(
            const_cast<BYTE*>(snapshot.descriptor.data()));
        PSID owner = nullptr;
        PSID group = nullptr;
        PACL dacl = nullptr;
        BOOL daclPresent = FALSE;
        BOOL daclDefaulted = FALSE;
        SECURITY_DESCRIPTOR_CONTROL control = 0;
        DWORD revision = 0;
        if (!GetSecurityDescriptorOwner(descriptor, &owner, nullptr) ||
            !GetSecurityDescriptorGroup(descriptor, &group, nullptr) ||
            !GetSecurityDescriptorDacl(descriptor, &daclPresent, &dacl, &daclDefaulted) ||
            !GetSecurityDescriptorControl(descriptor, &control, &revision)) {
            return false;
        }
        DWORD securityInformation = OWNER_SECURITY_INFORMATION |
            GROUP_SECURITY_INFORMATION |
            DACL_SECURITY_INFORMATION;
        securityInformation |= (control & SE_DACL_PROTECTED)
            ? PROTECTED_DACL_SECURITY_INFORMATION
            : UNPROTECTED_DACL_SECURITY_INFORMATION;
        const DWORD result = SetNamedSecurityInfoW(
            const_cast<LPWSTR>(snapshot.path.c_str()),
            SE_FILE_OBJECT,
            securityInformation,
            owner,
            group,
            daclPresent ? dacl : nullptr,
            nullptr);
        return result == ERROR_SUCCESS;
    }

    bool hasSidAce(const std::wstring& path) const {
        PSECURITY_DESCRIPTOR descriptor = nullptr;
        PACL ignoredDacl = nullptr;
        PSID ignoredOwner = nullptr;
        PSID ignoredGroup = nullptr;
        const DWORD readResult = GetNamedSecurityInfoW(
            const_cast<LPWSTR>(path.c_str()),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &ignoredOwner,
            &ignoredGroup,
            &ignoredDacl,
            nullptr,
            &descriptor);
        if (readResult != ERROR_SUCCESS || descriptor == nullptr) return false;
        BOOL present = FALSE;
        BOOL defaulted = FALSE;
        PACL dacl = nullptr;
        if (!GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted) || !present || dacl == nullptr) {
            LocalFree(descriptor);
            return false;
        }
        bool found = false;
        for (DWORD index = 0; index < dacl->AceCount; ++index) {
            LPVOID rawAce = nullptr;
            if (!GetAce(dacl, index, &rawAce) || rawAce == nullptr) continue;
            const auto* header = static_cast<const ACE_HEADER*>(rawAce);
            PSID aceSid = nullptr;
            if (header->AceType == ACCESS_ALLOWED_ACE_TYPE) {
                aceSid = reinterpret_cast<PSID>(const_cast<DWORD*>(
                    &static_cast<const ACCESS_ALLOWED_ACE*>(rawAce)->SidStart));
            } else if (header->AceType == ACCESS_DENIED_ACE_TYPE) {
                aceSid = reinterpret_cast<PSID>(const_cast<DWORD*>(
                    &static_cast<const ACCESS_DENIED_ACE*>(rawAce)->SidStart));
            }
            if (aceSid != nullptr && EqualSid(aceSid, sid)) {
                found = true;
                break;
            }
        }
        LocalFree(descriptor);
        return found;
    }

    bool removeGrantFromNewObjects() {
        bool ok = true;
        std::error_code ec;
        fs::recursive_directory_iterator iterator(fs::path(root), fs::directory_options::skip_permission_denied, ec);
        if (ec) return false;
        auto removeFrom = [&](const fs::path& path) {
            if (originalPaths.find(key(path.wstring())) != originalPaths.end()) return;
            if (!removeGrant(path.wstring())) ok = false;
        };
        for (; iterator != fs::recursive_directory_iterator(); iterator.increment(ec)) {
            if (ec) return false;
            const auto attributes = GetFileAttributesW(iterator->path().wstring().c_str());
            if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                ok = false;
                continue;
            }
            removeFrom(iterator->path());
        }
        return ok;
    }

    bool removeGrant(const std::wstring& path) {
        PSECURITY_DESCRIPTOR descriptor = nullptr;
        PACL ignoredDacl = nullptr;
        PSID ignoredOwner = nullptr;
        PSID ignoredGroup = nullptr;
        const DWORD readResult = GetNamedSecurityInfoW(
            const_cast<LPWSTR>(path.c_str()),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &ignoredOwner,
            &ignoredGroup,
            &ignoredDacl,
            nullptr,
            &descriptor);
        if (readResult != ERROR_SUCCESS || descriptor == nullptr) return false;
        BOOL present = FALSE;
        BOOL defaulted = FALSE;
        PACL dacl = nullptr;
        const bool readDacl = GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted) != FALSE;
        if (!readDacl) {
            LocalFree(descriptor);
            return false;
        }
        EXPLICIT_ACCESSW revoke{};
        revoke.grfAccessMode = REVOKE_ACCESS;
        revoke.Trustee.TrusteeForm = TRUSTEE_IS_SID;
        revoke.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
        revoke.Trustee.ptstrName = reinterpret_cast<LPWSTR>(sid);
        PACL updated = nullptr;
        const DWORD aclResult = SetEntriesInAclW(1, &revoke, present ? dacl : nullptr, &updated);
        if (aclResult != ERROR_SUCCESS) {
            LocalFree(descriptor);
            return false;
        }
        const DWORD setResult = SetNamedSecurityInfoW(
            const_cast<LPWSTR>(path.c_str()),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION,
            nullptr,
            nullptr,
            updated,
            nullptr);
        LocalFree(updated);
        LocalFree(descriptor);
        return setResult == ERROR_SUCCESS;
    }
};

bool hasControlCharacter(const std::wstring& value) {
    return std::any_of(value.begin(), value.end(), [](wchar_t c) {
        return c == L'\0' || c == L'\r' || c == L'\n';
    });
}

bool isWithin(const std::wstring& child, const std::wstring& root) {
    const std::wstring childKey = AclTransaction::key(child);
    const std::wstring rootKey = AclTransaction::key(root);
    if (childKey == rootKey) return true;
    return childKey.size() > rootKey.size() &&
        childKey.compare(0, rootKey.size(), rootKey) == 0 &&
        (rootKey.back() == L'\\' || childKey[rootKey.size()] == L'\\');
}

bool canonicalPath(const std::wstring& input, std::wstring& output) {
    wchar_t buffer[32768]{};
    const DWORD length = GetFullPathNameW(input.c_str(), static_cast<DWORD>(std::size(buffer)), buffer, nullptr);
    if (length == 0 || length >= std::size(buffer)) return false;
    output.assign(buffer, length);
    while (output.size() > 3 && (output.back() == L'\\' || output.back() == L'/')) output.pop_back();
    return true;
}

std::wstring quoteArg(const std::wstring& value) {
    if (value.find_first_of(L" \t\"") == std::wstring::npos) return value;
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (wchar_t c : value) {
        if (c == L'\\') {
            ++slashes;
        } else if (c == L'"') {
            result.append(slashes * 2 + 1, L'\\');
            result += L'"';
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            slashes = 0;
            result += c;
        }
    }
    result.append(slashes * 2, L'\\');
    result += L'"';
    return result;
}

HRESULT createProfile(Profile& profile) {
    profile.name = L"OmniCode.Agent." + std::to_wstring(GetCurrentProcessId()) + L"." +
        std::to_wstring(GetTickCount64() % 1000000000ULL);
    return CreateAppContainerProfile(
        profile.name.c_str(),
        L"OmniCode Agent",
        L"Per-command OmniCode workspace sandbox",
        nullptr,
        0,
        &profile.sid);
}

bool grantProfileFolder(const std::wstring& path, PSID sid, std::wstring& error) {
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    PACL ignoredDacl = nullptr;
    PSID ignoredOwner = nullptr;
    PSID ignoredGroup = nullptr;
    const DWORD readResult = GetNamedSecurityInfoW(
        const_cast<LPWSTR>(path.c_str()),
        SE_FILE_OBJECT,
        OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
        &ignoredOwner,
        &ignoredGroup,
        &ignoredDacl,
        nullptr,
        &descriptor);
    if (readResult != ERROR_SUCCESS || descriptor == nullptr) {
        if (descriptor != nullptr) LocalFree(descriptor);
        error = L"cannot read the AppContainer profile security descriptor";
        return false;
    }
    BOOL present = FALSE;
    BOOL defaulted = FALSE;
    PACL dacl = nullptr;
    if (!GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted)) {
        LocalFree(descriptor);
        error = L"cannot read the AppContainer profile DACL";
        return false;
    }
    EXPLICIT_ACCESSW entry{};
    entry.grfAccessPermissions = GENERIC_READ | GENERIC_WRITE | DELETE | FILE_DELETE_CHILD;
    entry.grfAccessMode = GRANT_ACCESS;
    entry.grfInheritance = SUB_CONTAINERS_AND_OBJECTS_INHERIT;
    entry.Trustee.TrusteeForm = TRUSTEE_IS_SID;
    entry.Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
    entry.Trustee.ptstrName = reinterpret_cast<LPWSTR>(sid);
    PACL updated = nullptr;
    const DWORD aclResult = SetEntriesInAclW(1, &entry, present ? dacl : nullptr, &updated);
    if (aclResult != ERROR_SUCCESS || updated == nullptr) {
        LocalFree(descriptor);
        if (updated != nullptr) LocalFree(updated);
        error = L"cannot build the AppContainer profile ACL";
        return false;
    }
    const DWORD setResult = SetNamedSecurityInfoW(
        const_cast<LPWSTR>(path.c_str()),
        SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION,
        nullptr,
        nullptr,
        updated,
        nullptr);
    LocalFree(updated);
    LocalFree(descriptor);
    if (setResult != ERROR_SUCCESS) {
        error = L"cannot grant the AppContainer profile ACL";
        return false;
    }
    return true;
}

bool configureProfileEnvironment(
    const std::wstring& profileName,
    PSID sid,
    std::vector<wchar_t>& environment,
    std::wstring& error) {
    PWSTR folder = nullptr;
    HRESULT result = GetAppContainerFolderPath(profileName.c_str(), &folder);
    std::wstring profileFolder;
    if (SUCCEEDED(result) && folder != nullptr) {
        profileFolder.assign(folder);
        CoTaskMemFree(folder);
    } else {
        // GetAppContainerFolderPath expects a package identity on some Windows
        // builds, while CreateAppContainerProfile accepts a broker-created
        // moniker. The documented profile layout is still deterministic for
        // this desktop-created profile, so resolve it from the user's local
        // application-data root as a fail-closed fallback.
        if (folder != nullptr) CoTaskMemFree(folder);
        PWSTR localAppData = nullptr;
        result = SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_DEFAULT, nullptr, &localAppData);
        if (FAILED(result) || localAppData == nullptr) {
            error = L"cannot resolve the AppContainer profile folder";
            if (localAppData != nullptr) CoTaskMemFree(localAppData);
            return false;
        }
        profileFolder = (fs::path(localAppData) / L"Packages" / profileName / L"AC").wstring();
        CoTaskMemFree(localAppData);
    }
    const std::wstring temp = profileFolder + L"\\Temp";
    if (!CreateDirectoryW(profileFolder.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) {
        error = L"cannot create the AppContainer profile folder";
        return false;
    }
    if (!CreateDirectoryW(temp.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) {
        error = L"cannot create the AppContainer temporary folder";
        return false;
    }
    const auto tempAttributes = GetFileAttributesW(temp.c_str());
    if (tempAttributes == INVALID_FILE_ATTRIBUTES || (tempAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        error = L"AppContainer temporary path is not a directory";
        return false;
    }
    // Do not let CreateProcess inherit the broker/JVM environment: it may contain API keys,
    // proxy credentials, or arbitrary user-controlled variables. Build an explicit minimal
    // environment block instead. The child still needs the Windows loader roots to start
    // ordinary commands such as whoami.exe.
    wchar_t windowsDirectory[32768]{};
    const DWORD windowsLength = GetWindowsDirectoryW(windowsDirectory, static_cast<DWORD>(std::size(windowsDirectory)));
    const std::wstring windowsRoot = windowsLength > 0 && windowsLength < std::size(windowsDirectory)
        ? std::wstring(windowsDirectory, windowsLength)
        : L"C:\\Windows";
    const std::wstring safePath = windowsRoot + L"\\System32;" + windowsRoot + L";" + windowsRoot + L"\\System32\\Wbem";
    std::vector<std::wstring> values = {
        L"SystemRoot=" + windowsRoot,
        L"WINDIR=" + windowsRoot,
        L"Path=" + safePath,
        L"ComSpec=" + windowsRoot + L"\\System32\\cmd.exe",
        L"LOCALAPPDATA=" + profileFolder,
        L"TEMP=" + temp,
        L"TMP=" + temp,
        L"USERPROFILE=" + profileFolder,
        L"HOME=" + profileFolder,
    };
    std::sort(values.begin(), values.end(), [](const std::wstring& left, const std::wstring& right) {
        return std::lexicographical_compare(
            left.begin(), left.end(), right.begin(), right.end(),
            [](wchar_t a, wchar_t b) { return std::towlower(a) < std::towlower(b); });
    });
    environment.clear();
    for (const auto& value : values) {
        environment.insert(environment.end(), value.begin(), value.end());
        environment.push_back(L'\0');
    }
    // A Windows environment block is terminated by an additional NUL.
    environment.push_back(L'\0');
    if (!grantProfileFolder(profileFolder, sid, error) || !grantProfileFolder(temp, sid, error)) {
        return false;
    }
    return true;
}

int runChild(const std::wstring& workspace, const std::wstring& cwd, bool readOnly,
             const std::vector<std::wstring>& childArguments, Profile& profile) {
    std::wstring error;
    AclTransaction transaction;
    if (!transaction.begin(workspace, profile.sid, readOnly, error)) {
        std::wcerr << L"OMNICODE_APPCONTAINER_ACL_FAILED: " << error << L"\n";
        return 72;
    }
    std::vector<wchar_t> safeEnvironment;
    if (!configureProfileEnvironment(profile.name, profile.sid, safeEnvironment, error)) {
        transaction.restore();
        std::wcerr << L"OMNICODE_APPCONTAINER_PROFILE_FAILED: " << error << L"\n";
        return 72;
    }

    std::wstring commandLine;
    for (const auto& argument : childArguments) {
        if (!commandLine.empty()) commandLine += L' ';
        commandLine += quoteArg(argument);
    }
    std::vector<wchar_t> mutableCommand(commandLine.begin(), commandLine.end());
    mutableCommand.push_back(L'\0');

    SIZE_T attributeBytes = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attributeBytes);
    if (attributeBytes == 0) {
        transaction.restore();
        std::wcerr << L"OMNICODE_APPCONTAINER_ATTRIBUTE_FAILED\n";
        return 72;
    }
    auto* attributes = static_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(HeapAlloc(GetProcessHeap(), 0, attributeBytes));
    if (attributes == nullptr || !InitializeProcThreadAttributeList(attributes, 1, 0, &attributeBytes)) {
        if (attributes != nullptr) HeapFree(GetProcessHeap(), 0, attributes);
        transaction.restore();
        std::wcerr << L"OMNICODE_APPCONTAINER_ATTRIBUTE_FAILED\n";
        return 72;
    }
    SECURITY_CAPABILITIES capabilities{};
    capabilities.AppContainerSid = profile.sid;
    if (!UpdateProcThreadAttribute(
            attributes,
            0,
            PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
            &capabilities,
            sizeof(capabilities),
            nullptr,
            nullptr)) {
        DeleteProcThreadAttributeList(attributes);
        HeapFree(GetProcessHeap(), 0, attributes);
        transaction.restore();
        std::wcerr << L"OMNICODE_APPCONTAINER_ATTRIBUTE_FAILED\n";
        return 72;
    }

    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.lpAttributeList = attributes;
    PROCESS_INFORMATION processInfo{};
    const BOOL created = CreateProcessW(
        nullptr,
        mutableCommand.data(),
        nullptr,
        nullptr,
        TRUE,
        EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
        safeEnvironment.data(),
        cwd.c_str(),
        &startup.StartupInfo,
        &processInfo);
    DeleteProcThreadAttributeList(attributes);
    HeapFree(GetProcessHeap(), 0, attributes);

    if (!created) {
        const DWORD errorCode = GetLastError();
        transaction.restore();
        std::wcerr << L"OMNICODE_APPCONTAINER_CREATE_FAILED: " << errorCode << L"\n";
        return 72;
    }

    CloseHandle(processInfo.hThread);
    WaitForSingleObject(processInfo.hProcess, INFINITE);
    DWORD exitCode = 1;
    GetExitCodeProcess(processInfo.hProcess, &exitCode);
    CloseHandle(processInfo.hProcess);
    if (!transaction.restore()) {
        std::wcerr << L"OMNICODE_APPCONTAINER_CLEANUP_FAILED\n";
        return 73;
    }
    return static_cast<int>(exitCode);
}

int usage() {
    std::wcerr << L"Usage: omnicode-appcontainer-host.exe --probe | --workspace <path> --cwd <path> [--read-only|--read-write] -- <argv...>\n";
    return 64;
}

} // namespace

int wmain(int argc, wchar_t* argv[]) {
    // A TerminateProcess from the IDE can interrupt the broker between ACL grant and
    // restoration. Recover journals from an earlier run before accepting a new command.
    AclTransaction::recoverOrphanedJournals();
    if (argc == 2 && std::wstring(argv[1]) == L"--probe") {
        Profile profile;
        const HRESULT result = createProfile(profile);
        if (FAILED(result) || profile.sid == nullptr) {
            std::wcerr << L"OMNICODE_APPCONTAINER_PROBE_FAILED: " << std::hex << result << L"\n";
            return 70;
        }
        std::wstring error;
        std::vector<wchar_t> probeEnvironment;
        if (!configureProfileEnvironment(profile.name, profile.sid, probeEnvironment, error)) {
            std::wcerr << L"OMNICODE_APPCONTAINER_PROBE_FAILED: " << error << L"\n";
            return 70;
        }
        SIZE_T bytes = 0;
        InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
        if (bytes == 0) return 70;
        std::wcout << L"OMNICODE_APPCONTAINER_PROBE_OK\n";
        return 0;
    }
    if (argc < 7 || std::wstring(argv[1]) != L"--workspace") return usage();

    std::wstring workspace;
    std::wstring cwd;
    bool readOnly = false;
    bool modeSet = false;
    std::vector<std::wstring> child;
    for (int index = 1; index < argc; ++index) {
        const std::wstring value = argv[index];
        if (value == L"--workspace" && index + 1 < argc) {
            workspace = argv[++index];
        } else if (value == L"--cwd" && index + 1 < argc) {
            cwd = argv[++index];
        } else if (value == L"--read-only") {
            readOnly = true;
            modeSet = true;
        } else if (value == L"--read-write") {
            readOnly = false;
            modeSet = true;
        } else if (value == L"--") {
            for (++index; index < argc; ++index) child.emplace_back(argv[index]);
            break;
        } else {
            return usage();
        }
    }
    if (workspace.empty() || cwd.empty() || !modeSet || child.empty() || child.size() > kMaxArguments) return usage();
    size_t totalCharacters = 0;
    for (const auto& value : child) {
        if (hasControlCharacter(value)) return usage();
        totalCharacters += value.size();
    }
    if (totalCharacters > kMaxArgumentCharacters) return usage();
    std::wstring canonicalWorkspace;
    std::wstring canonicalCwd;
    if (!canonicalPath(workspace, canonicalWorkspace) || !canonicalPath(cwd, canonicalCwd) ||
        !isWithin(canonicalCwd, canonicalWorkspace)) {
        std::wcerr << L"OMNICODE_APPCONTAINER_PATH_FAILED\n";
        return 71;
    }
    const auto attributes = GetFileAttributesW(canonicalWorkspace.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        std::wcerr << L"OMNICODE_APPCONTAINER_PATH_FAILED\n";
        return 71;
    }

    Profile profile;
    const HRESULT profileResult = createProfile(profile);
    if (FAILED(profileResult) || profile.sid == nullptr) {
        std::wcerr << L"OMNICODE_APPCONTAINER_PROFILE_FAILED: " << std::hex << profileResult << L"\n";
        return 72;
    }
    return runChild(canonicalWorkspace, canonicalCwd, readOnly, child, profile);
}
