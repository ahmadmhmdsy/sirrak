package app.sirrak.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 acceptance, first half: "kept vs once behaviour verified by test" (docs/31 section 6.4). These
 * tests pin the three facts the UI copy claims: ONCE is never indexed, ONCE does not survive, and the
 * access switch cannot make a temporary item readable.
 */
class MediaPolicyTest {

    private fun record(
        id: String = "m-1",
        kind: MediaKind = MediaKind.DOCUMENT,
        retention: MediaRetention = MediaRetention.KEEP,
        access: MediaAccess = MediaAccess.GRANTED,
        transcriptStatus: MediaTranscriptStatus = MediaTranscriptStatus.NOT_APPLICABLE,
        conversationId: String? = null,
        consumedAt: Long? = null,
    ): MediaRecord = MediaRecord(
        id = id,
        kind = kind,
        displayName = "ملف تجريبي",
        sizeBytes = 10L,
        retention = retention,
        access = access,
        createdAt = 1L,
        fileName = id + ".txt",
        transcriptStatus = transcriptStatus,
        conversationId = conversationId,
        consumedAt = consumedAt,
    )

    @Test
    fun keptAndGrantedDocumentIsIndexableAndSurvivesRestart() {
        val consequences = MediaRules.consequences(record())
        assertTrue(consequences.indexable)
        assertTrue(consequences.agentReadable)
        assertTrue(consequences.survivesRestart)
        assertFalse(consequences.deletedWithConversation)
        assertFalse(consequences.deletedAfterUse)
        assertTrue(MediaRules.shouldIndex(record()))
    }

    @Test
    fun deniedAccessKeepsTheItemButNeverMakesItReadable() {
        val item = record(access = MediaAccess.DENIED)
        val consequences = MediaRules.consequences(item)
        assertFalse(consequences.indexable)
        assertFalse(consequences.agentReadable)
        assertTrue(consequences.survivesRestart)
        assertFalse(MediaRules.shouldIndex(item))
    }

    @Test
    fun onceIsNeverIndexedEvenWhenAccessIsGranted() {
        val item = record(retention = MediaRetention.ONCE, access = MediaAccess.GRANTED)
        val consequences = MediaRules.consequences(item)
        assertFalse(consequences.indexable)
        assertFalse(consequences.agentReadable)
        assertFalse(MediaRules.shouldIndex(item))
    }

    @Test
    fun onceNeverSurvivesRestart() {
        val consequences = MediaRules.consequences(record(retention = MediaRetention.ONCE))
        assertFalse(consequences.survivesRestart)
        assertTrue(consequences.deletedWithConversation)
        assertTrue(consequences.deletedAfterUse)
    }

    @Test
    fun imagesAreStoredButNeverIndexedInV1() {
        val image = record(kind = MediaKind.IMAGE)
        assertFalse(MediaRules.kindCanBeIndexed(MediaKind.IMAGE))
        assertFalse(MediaRules.shouldIndex(image))
        assertFalse(MediaRules.consequences(image).indexable)
    }

    @Test
    fun audioIsIndexableOnlyAfterItsTranscriptIsReady() {
        val pending = record(kind = MediaKind.AUDIO, transcriptStatus = MediaTranscriptStatus.PENDING)
        val failed = record(kind = MediaKind.AUDIO, transcriptStatus = MediaTranscriptStatus.FAILED)
        val ready = record(kind = MediaKind.AUDIO, transcriptStatus = MediaTranscriptStatus.READY)
        assertFalse(MediaRules.shouldIndex(pending))
        assertFalse(MediaRules.shouldIndex(failed))
        assertTrue(MediaRules.shouldIndex(ready))
    }

    @Test
    fun accessSwitchCannotGrantATemporaryItem() {
        assertTrue(MediaRules.canGrantAgentAccess(MediaRetention.KEEP, MediaKind.DOCUMENT))
        assertFalse(MediaRules.canGrantAgentAccess(MediaRetention.ONCE, MediaKind.DOCUMENT))
        assertFalse(MediaRules.canGrantAgentAccess(MediaRetention.KEEP, MediaKind.IMAGE))
    }

    @Test
    fun policyDefaultAppliesToAnImportWithoutAnExplicitDecision() {
        val policy = MediaPolicy(
            defaultRetention = MediaRetention.ONCE,
            defaultAccess = MediaAccess.DENIED,
        )
        val inherited = MediaRules.decide(policy, retentionOverride = null, accessOverride = null)
        assertEquals(MediaRetention.ONCE, inherited.retention)
        assertEquals(MediaAccess.DENIED, inherited.access)
    }

    @Test
    fun explicitDecisionWinsOverThePolicy() {
        val policy = MediaPolicy(defaultRetention = MediaRetention.ONCE)
        val decided = MediaRules.decide(policy, MediaRetention.KEEP, MediaAccess.GRANTED)
        assertEquals(MediaRetention.KEEP, decided.retention)
        assertEquals(MediaAccess.GRANTED, decided.access)
    }

    @Test
    fun onceItemIsAliveUntilConsumed() {
        val alive = record(retention = MediaRetention.ONCE)
        assertEquals(MediaOnce.State.ALIVE, MediaOnce.state(alive))
        val consumed = MediaOnce.consume(alive, at = 9L)
        assertEquals(9L, consumed.consumedAt)
        assertEquals(MediaOnce.State.CONSUMED, MediaOnce.state(consumed))
    }

    @Test
    fun keptItemHasNoOnceStateAndConsumingItChangesNothing() {
        val kept = record()
        assertNull(MediaOnce.state(kept))
        val consumed = MediaOnce.consume(kept, at = 9L)
        assertNull(consumed.consumedAt)
        assertEquals(kept, consumed)
    }

    @Test
    fun endingASessionDeletesEveryOnceItemAndKeepsKeptItems() {
        val kept = record(id = "keep-1", retention = MediaRetention.KEEP)
        val once = record(id = "once-1", retention = MediaRetention.ONCE)
        val expiry = MediaOnce.expireSession(listOf(kept, once))
        assertEquals(listOf("once-1"), expiry.deletedIds)
        assertEquals(listOf(kept), expiry.retained)
        assertTrue(expiry.changed)
    }

    @Test
    fun closingAConversationDeletesOnlyItsOwnOnceItems() {
        val keptSameConversation = record(id = "keep", retention = MediaRetention.KEEP, conversationId = "chat-1")
        val onceSameConversation = record(id = "once-here", retention = MediaRetention.ONCE, conversationId = "chat-1")
        val onceOtherConversation = record(id = "once-elsewhere", retention = MediaRetention.ONCE, conversationId = "chat-2")
        val onceConsumed = record(id = "once-used", retention = MediaRetention.ONCE, conversationId = "chat-2", consumedAt = 5L)
        val expiry = MediaOnce.expireConversation(
            listOf(keptSameConversation, onceSameConversation, onceOtherConversation, onceConsumed),
            conversationId = "chat-1",
        )
        assertEquals(listOf("once-here", "once-used"), expiry.deletedIds.sorted())
        assertEquals(listOf("keep", "once-elsewhere"), expiry.retained.map { it.id }.sorted())
    }

    @Test
    fun consumedOnceItemsExpireBeforeTheirConversationCloses() {
        val alive = record(id = "alive", retention = MediaRetention.ONCE)
        val consumed = record(id = "used", retention = MediaRetention.ONCE, consumedAt = 7L)
        val expiry = MediaOnce.expireConsumed(listOf(alive, consumed))
        assertEquals(listOf("used"), expiry.deletedIds)
        assertEquals(listOf("alive"), expiry.retained.map { it.id })
    }
}
