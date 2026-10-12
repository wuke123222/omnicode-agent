#include <windows.h>

namespace {

bool commandLineContains(const wchar_t* needle) {
    const wchar_t* commandLine = GetCommandLineW();
    if (commandLine == nullptr || needle == nullptr || *needle == L'\0') return false;
    for (const wchar_t* start = commandLine; *start != L'\0'; ++start) {
        const wchar_t* candidate = start;
        const wchar_t* expected = needle;
        while (*candidate != L'\0' && *expected != L'\0' && *candidate == *expected) {
            ++candidate;
            ++expected;
        }
        if (*expected == L'\0') return true;
    }
    return false;
}

} // namespace

extern "C" void WINAPI OmniCodeSmokeChildEntry() {
    if (commandLineContains(L"--assert-secret-absent")) {
        wchar_t secret[2]{};
        const DWORD secretLength = static_cast<DWORD>(sizeof(secret) / sizeof(secret[0]));
        if (GetEnvironmentVariableW(L"OMNICODE_TEST_SECRET", secret, secretLength) > 0) {
            ExitProcess(17);
        }
    }
    if (commandLineContains(L"--stdio")) {
        constexpr char marker[] = "OMNICODE_APPCONTAINER_STDIO_OK\r\n";
        const HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
        DWORD written = 0;
        if (output == nullptr || output == INVALID_HANDLE_VALUE ||
            !WriteFile(output, marker, static_cast<DWORD>(sizeof(marker) - 1), &written, nullptr) ||
            written != sizeof(marker) - 1) {
            ExitProcess(18);
        }
    }
    ExitProcess(0);
}
