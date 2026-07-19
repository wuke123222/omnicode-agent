package dev.omnicode.ui

import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.FileFlavorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttachmentDropSupportTest {
    @Test
    fun `attachment worker captures ordinary failures but preserves cancellation`() = runBlocking {
        val failed = captureAttachmentWork<String> { throw java.io.IOException("broken image") }

        assertTrue(failed.isFailure)
        assertFailsWith<CancellationException> {
            captureAttachmentWork<String> { throw CancellationException("disposed") }
        }
    }

    @Test
    fun `project tree file flavor provider resolves local paths`() {
        val file = Files.createTempFile("omnicode-project-drop", ".md")
        try {
            val payload = FileFlavorProvider { listOf(file.toFile()) }

            assertEquals(listOf(file.toAbsolutePath().normalize()), attachmentPathsFromDropPayload(payload))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `native desktop transfer resolves local paths`() {
        val file = Files.createTempFile("omnicode-desktop-drop", ".png")
        try {
            val transferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                    flavor == DataFlavor.javaFileListFlavor

                override fun getTransferData(flavor: DataFlavor): Any {
                    check(isDataFlavorSupported(flavor))
                    return listOf(file.toFile())
                }
            }
            val payload = DnDNativeTarget.EventInfo(transferable.transferDataFlavors, transferable)

            assertEquals(listOf(file.toAbsolutePath().normalize()), attachmentPathsFromDropPayload(payload))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
