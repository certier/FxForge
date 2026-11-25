package dev.fxforge.app

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object ProjectTextContent {
    const val MAX_BYTES = 2 * 1024 * 1024

    fun read(resolver: ContentResolver, uri: Uri): String {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Android could not open this file for reading")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) {
                    throw IOException("Files larger than 2 MiB cannot be opened in the editor")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decode(bytes)
    }

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        val bytes = encode(text)
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Android could not open this file for writing")
        output.use {
            it.write(bytes)
            it.flush()
        }
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) {
            throw IOException("Files larger than 2 MiB cannot be opened in the editor")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw IOException("This file is not valid UTF-8 text", exception)
        }
        if ('\u0000' in text) {
            throw IOException("Binary files cannot be opened in the text editor")
        }
        return text
    }

    private fun encode(text: String): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(text))
        } catch (exception: CharacterCodingException) {
            throw IOException("Text contains characters that cannot be encoded as UTF-8", exception)
        }
        if (buffer.remaining() > MAX_BYTES) {
            throw IOException("Files larger than 2 MiB cannot be saved from the editor")
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
