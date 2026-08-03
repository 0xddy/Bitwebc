package cn.lmcw.bitwebc.filechooser.accept

import cn.lmcw.bitwebc.filechooser.accept.FileChooserAcceptResolver.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChooserAcceptResolverTest {

    @Test
    fun `empty accept falls back to any file`() {
        assertEquals(listOf("*/*"), FileChooserAcceptResolver.normalizeAcceptTypes(null))
        assertEquals(listOf("*/*"), FileChooserAcceptResolver.normalizeAcceptTypes(emptyArray()))
        assertEquals(listOf("*/*"), FileChooserAcceptResolver.normalizeAcceptTypes(arrayOf(" ")))
    }

    @Test
    fun `splits comma separated values and normalizes case`() {
        assertEquals(
            listOf("image/png", "application/pdf", "video/mp4"),
            FileChooserAcceptResolver.normalizeAcceptTypes(
                arrayOf(" IMAGE/PNG, .PDF ", "video/mp4")
            )
        )
    }

    @Test
    fun `converts common extensions to MIME types`() {
        assertEquals(
            listOf(
                "image/jpeg",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ),
            FileChooserAcceptResolver.normalizeAcceptTypes(arrayOf(".jpg,.pdf,.docx"))
        )
    }

    @Test
    fun `never passes an unknown extension through as a MIME type`() {
        assertEquals(
            listOf("*/*"),
            FileChooserAcceptResolver.normalizeAcceptTypes(arrayOf(".madeup"))
        )
    }

    @Test
    fun `invalid tokens are ignored`() {
        assertEquals(
            listOf("application/pdf"),
            FileChooserAcceptResolver.normalizeAcceptTypes(arrayOf("not-a-mime,.pdf"))
        )
    }

    @Test
    fun `mixed image and document is not classified as image only`() {
        val types = FileChooserAcceptResolver.normalizeAcceptTypes(arrayOf("image/*,.pdf"))

        assertEquals(listOf("image/*", "application/pdf"), types)
        assertEquals(MediaType.UNKNOWN, FileChooserAcceptResolver.resolveMediaType(types))
    }

    @Test
    fun `all MIME types must share a family for media classification`() {
        assertEquals(
            MediaType.IMAGE,
            FileChooserAcceptResolver.resolveMediaType(listOf("image/png", "image/jpeg"))
        )
        assertEquals(
            MediaType.VIDEO,
            FileChooserAcceptResolver.resolveMediaType(listOf("video/mp4", "video/webm"))
        )
        assertEquals(
            MediaType.AUDIO,
            FileChooserAcceptResolver.resolveMediaType(listOf("audio/mpeg", "audio/wav"))
        )
        assertEquals(
            MediaType.UNKNOWN,
            FileChooserAcceptResolver.resolveMediaType(listOf("*/*"))
        )
    }

    @Test
    fun `duplicate and parameterized MIME types are normalized`() {
        assertEquals(
            listOf("image/jpeg"),
            FileChooserAcceptResolver.normalizeAcceptTypes(
                arrayOf("image/jpeg; quality=90", "IMAGE/JPEG")
            )
        )
    }
}
