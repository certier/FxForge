package dev.fxforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProjectTextContentTest {
    @Test
    fun decodesUtf8Text() {
        assertEquals("fun main() {}\n", ProjectTextContent.decode("fun main() {}\n".toByteArray()))
    }

    @Test
    fun rejectsInvalidUtf8() {
        assertThrows(IOException::class.java) {
            ProjectTextContent.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun rejectsBinaryContent() {
        assertThrows(IOException::class.java) {
            ProjectTextContent.decode(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
        }
    }

    @Test
    fun rejectsFilesAboveEditorLimit() {
        assertThrows(IOException::class.java) {
            ProjectTextContent.decode(ByteArray(ProjectTextContent.MAX_BYTES + 1))
        }
    }
}
