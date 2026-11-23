package app.sirrak.core.runtime

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 attachment foundation contract: one model for documents, images, audio transcripts and shared
 * text; validation caps; capability gating; untrusted-data prompt rendering. No Android classes: the
 * whole policy is testable on the JVM and the app layer only supplies bytes and storage.
 */
class AttachmentsTest {
    @Test
    fun mimeTypesMapToKinds() {
        assertEquals(AttachmentKind.DOCUMENT, AttachmentPolicy.kindOf("application/pdf"))
        assertEquals(AttachmentKind.DOCUMENT, AttachmentPolicy.kindOf("text/plain"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("image/jpeg"))
        assertEquals(AttachmentKind.AUDIO_TRANSCRIPT, AttachmentPolicy.kindOf("audio/mpeg"))
        assertNull(AttachmentPolicy.kindOf("application/zip"))
        assertNull(AttachmentPolicy.kindOf(null))
        assertNull(AttachmentPolicy.kindOf("  "))
    }

    @Test
    fun declaredMimeTypeWinsAndExtensionFillsTheGap() {
        assertEquals("application/pdf", AttachmentPolicy.mimeOf("scan.body", "application/pdf"))
        assertEquals("image/jpeg", AttachmentPolicy.mimeOf("photo.JPG", null))
        assertEquals("text/markdown", AttachmentPolicy.mimeOf("notes.md", null))
        assertEquals("application/epub+zip", AttachmentPolicy.mimeOf("book.epub", null))
        assertEquals("application/octet-stream", AttachmentPolicy.mimeOf("mystery.bin", null))
        assertEquals("application/pdf", AttachmentPolicy.mimeOf("x.pdf", "APPLICATION/PDF"))
    }

    @Test
    fun extensionsAreStableForLocalFiles() {
        assertEquals("pdf", AttachmentPolicy.extensionOf("application/pdf"))
        assertEquals("jpg", AttachmentPolicy.extensionOf("image/jpeg"))
        assertEquals("txt", AttachmentPolicy.extensionOf("text/plain"))
        assertEquals("bin", AttachmentPolicy.extensionOf("application/octet-stream"))
    }

    @Test
    fun documentValidationAcceptsCapsAndRejectsOversizeUnsupportedAndEmpty() {
        val limits = AttachmentLimits(documentBytes = 1000L)
        assertTrue(
            AttachmentPolicy.validateFile(null, "notes.md", 500L, limits) is AttachmentValidation.Accepted,
        )
        assertEquals(
            AttachmentRejection.TOO_LARGE,
            (AttachmentPolicy.validateFile(null, "notes.md", 1001L, limits) as AttachmentValidation.Rejected).reason,
        )
        assertEquals(
            AttachmentRejection.UNSUPPORTED_TYPE,
            (AttachmentPolicy.validateFile(null, "archive.zip", 10L, limits) as AttachmentValidation.Rejected).reason,
        )
        assertEquals(
            AttachmentRejection.EMPTY,
            (AttachmentPolicy.validateFile(null, "notes.md", 0L, limits) as AttachmentValidation.Rejected).reason,
        )
    }

    @Test
    fun imageAndAudioUseTheirOwnCaps() {
        val limits = AttachmentLimits(imageBytes = 10L, audioBytes = 20L)
        assertEquals(
            AttachmentRejection.TOO_LARGE,
            (AttachmentPolicy.validateFile("image/png", "a.png", 11L, limits) as AttachmentValidation.Rejected).reason,
        )
        assertEquals(
            AttachmentRejection.TOO_LARGE,
            (AttachmentPolicy.validateFile("audio/mpeg", "a.mp3", 21L, limits) as AttachmentValidation.Rejected).reason,
        )
        assertTrue(
            AttachmentPolicy.validateFile("image/png", "a.png", 10L, limits) is AttachmentValidation.Accepted,
        )
    }

    @Test
    fun acceptedValidationCarriesTheResolvedKindAndMime() {
        val accepted = AttachmentPolicy.validateFile("IMAGE/JPEG", "photo", 10L) as AttachmentValidation.Accepted
        assertEquals(AttachmentKind.IMAGE, accepted.kind)
        assertEquals("image/jpeg", accepted.mimeType)
        assertEquals(10L, accepted.sizeBytes)
    }

    @Test
    fun imageDimensionsOnlyAskForDownscaleAboveTheCap() {
        val limits = AttachmentLimits(imageDimension = 2048)
        assertTrue(AttachmentPolicy.imageNeedsDownscale(4000, 3000, limits))
        assertFalse(AttachmentPolicy.imageNeedsDownscale(2048, 1500, limits))
    }

    @Test
    fun sharedTextIsAFirstClassAttachmentKind() {
        val accepted = AttachmentPolicy.validateSharedText("hello") as AttachmentValidation.Accepted
        assertEquals(AttachmentKind.SHARED_TEXT, accepted.kind)
        assertEquals("text/plain", accepted.mimeType)
        assertEquals(5L, accepted.sizeBytes)
        assertEquals(
            AttachmentRejection.EMPTY,
            (AttachmentPolicy.validateSharedText("   ") as AttachmentValidation.Rejected).reason,
        )
        val limits = AttachmentLimits(sharedTextChars = 3)
        assertEquals(
            AttachmentRejection.TOO_LARGE,
            (AttachmentPolicy.validateSharedText("four", limits) as AttachmentValidation.Rejected).reason,
        )
    }

    @Test
    fun textOnlyEngineEnablesSharedTextAndExplainsTheRest() {
        val gate = AttachmentGate(EngineCapabilities.TextOnly)
        assertTrue(gate.isEnabled(AttachmentKind.SHARED_TEXT))
        assertEquals(
            AttachmentBlockReason.DOCUMENT_BACKEND,
            (gate.decision(AttachmentKind.DOCUMENT) as AttachmentDecision.Disabled).reason,
        )
        assertEquals(
            AttachmentBlockReason.VISION_MODEL,
            (gate.decision(AttachmentKind.IMAGE) as AttachmentDecision.Disabled).reason,
        )
        assertEquals(
            AttachmentBlockReason.AUDIO_BACKEND,
            (gate.decision(AttachmentKind.AUDIO_TRANSCRIPT) as AttachmentDecision.Disabled).reason,
        )
    }

    @Test
    fun capabilitiesOpenTheirOwnKindOnly() {
        val gate = AttachmentGate(
            EngineCapabilities(text = true, images = false, documents = true, audioTranscripts = true),
        )
        assertTrue(gate.isEnabled(AttachmentKind.DOCUMENT))
        assertTrue(gate.isEnabled(AttachmentKind.AUDIO_TRANSCRIPT))
        assertTrue(gate.isEnabled(AttachmentKind.SHARED_TEXT))
        assertFalse(gate.isEnabled(AttachmentKind.IMAGE))
        assertEquals(
            AttachmentBlockReason.VISION_MODEL,
            (gate.decision(AttachmentKind.IMAGE) as AttachmentDecision.Disabled).reason,
        )
    }

    @Test
    fun attachmentPromptIsLabelledDataAndSkippedWhenEmpty() {
        val text = Attachment(
            id = "a1",
            kind = AttachmentKind.SHARED_TEXT,
            displayName = "clip.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            localPath = "/tmp/clip.txt",
            extractedText = "line one",
        )
        val rendered = AttachmentPrompt.render(listOf(text))
        assertTrue(rendered.contains("<attachment"))
        assertTrue(rendered.contains("clip.txt"))
        assertTrue(rendered.contains("line one"))
        assertTrue(rendered.contains("</attachment>"))
        assertTrue(rendered.contains("untrusted"))
        assertEquals("", AttachmentPrompt.render(emptyList()))
        assertEquals("", AttachmentPrompt.render(listOf(text.copy(extractedText = null))))
    }

    @Test
    fun attachmentPromptIsBounded() {
        val long = Attachment(
            id = "a1",
            kind = AttachmentKind.SHARED_TEXT,
            displayName = "clip.txt",
            mimeType = "text/plain",
            sizeBytes = 100,
            extractedText = "x".repeat(100),
        )
        val rendered = AttachmentPrompt.render(listOf(long), maxChars = 40)
        assertTrue(rendered.length < 500)
        assertTrue(rendered.contains("truncated"))
        assertFalse(rendered.contains("x".repeat(100)))
    }

    @Test
    fun composeKeepsTheQuestionFirstAndAppendsAttachmentData() {
        val attachment = Attachment(
            id = "a1",
            kind = AttachmentKind.SHARED_TEXT,
            displayName = "clip.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            extractedText = "body",
        )
        val composed = AttachmentPrompt.compose("what is this?", listOf(attachment))
        assertTrue(composed.startsWith("what is this?"))
        assertTrue(composed.contains("body"))
        assertEquals("what is this?", AttachmentPrompt.compose("what is this?", emptyList()))
    }

    @Test
    fun chatTurnAttachmentsDefaultToEmptyAndRoundTripThroughExportJson() {
        assertTrue(ChatTurn(Role.USER, "hi").attachments.isEmpty())
        val attachment = Attachment(
            id = "a1",
            kind = AttachmentKind.SHARED_TEXT,
            displayName = "clip.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            localPath = "attachments/c1/a1.txt",
            extractedText = "body",
            meta = mapOf("source" to "clipboard"),
        )
        val conversation = Conversation(
            id = "c1",
            title = "with attachment",
            createdAt = 1L,
            updatedAt = 2L,
            turns = listOf(ChatTurn(Role.USER, "hi", listOf(attachment))),
        )
        val raw = ConversationExport.json(conversation)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val firstTurn = json["turns"]!!.jsonArray[0].jsonObject
        val stored = firstTurn["attachments"]!!.jsonArray[0].jsonObject
        assertEquals("a1", stored["id"]!!.jsonPrimitive.content)
        assertEquals("SHARED_TEXT", stored["kind"]!!.jsonPrimitive.content)
        assertEquals("body", stored["extractedText"]!!.jsonPrimitive.content)

        val restored = ConversationExport.fromJson(raw)!!
        val restoredAttachment = restored.turns.single().attachments.single()
        assertEquals(attachment, restoredAttachment)
    }
}
