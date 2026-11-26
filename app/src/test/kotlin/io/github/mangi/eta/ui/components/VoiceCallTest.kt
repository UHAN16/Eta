package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.voice.asr.CallSilenceDetector
import io.github.mangi.eta.agent.voice.asr.CallSilenceDetector.Event
import io.github.mangi.eta.agent.voice.tts.SpeechText
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class VoiceCallTest {
    @Test fun pauseEndsTurnOnceWithoutSendingEmptyAmbientAudio() {
        val detector = CallSilenceDetector(1000)
        for (time in 0L..2000L step 200) assertNull(detector.accept(0.01f, time))
        assertNull(detector.accept(0.058f, 2200))
        assertEquals(Event.STARTED, detector.accept(0.057f, 2400))
        assertNull(detector.accept(0.001f, 3200))
        assertNull(detector.accept(0.058f, 3300)) // resumed before the deadline
        assertEquals(Event.ENDED, detector.accept(0.007f, 4300))
        assertNull(detector.accept(0.006f, 5300))
    }

    @Test fun aShortClearUtteranceCanStartAndThenAutoEnd() {
        val detector = CallSilenceDetector(1200)
        assertEquals(Event.STARTED, detector.accept(0.14f, 200))
        assertNull(detector.accept(0.001f, 1200))
        assertEquals(Event.ENDED, detector.accept(0.001f, 1400))
    }

    @Test fun streamingReadsCompleteSentencesThenFlushesTailWithoutRepeating() {
        val queue = StreamingVoiceReply()
        assertNull(queue.next("a", "我们去", final = false))
        assertEquals("我们去散步。", queue.next("a", "我们去散步。然后", final = false))
        assertEquals("然后买杯咖啡！", queue.next("a", "我们去散步。然后买杯咖啡！", final = false))
        assertNull(queue.next("a", "我们去散步。然后买杯咖啡！也可以不去", final = false))
        assertEquals("也可以不去", queue.next("a", "我们去散步。然后买杯咖啡！也可以不去", final = true))
        assertNull(queue.next("a", "我们去散步。然后买杯咖啡！也可以不去", final = true))
    }

    @Test fun spokenQueueUsesSanitizedTextAndDoesNotSpeakAFinalCopyAgain() {
        val queue = StreamingVoiceReply()
        val live = SpeechText.readable("你好。\n\n```python\nprint(\"not spoken!\")")
        assertEquals("你好。", queue.next("a", live, final = false))
        val final = SpeechText.readable("你好。\n\n```python\nprint(\"not spoken!\")\n```\n\n再见。")
        assertEquals("再见。", queue.next("a", final, final = true))
        assertNull(queue.next("a", final, final = true))
    }

    @Test fun echoSuppressesPlaybackAndItsTailButNotLaterUserRepeats() {
        val echo = VoiceCallEchoWindow()
        echo.play("欢迎回来。", 100)
        echo.play("今天想聊什么？", 500)
        assertTrue(echo.isEcho("回来今天想聊", 600, playing = true))
        assertFalse(echo.isEcho("等一下，我想换个话题", 600, playing = true))
        echo.stopped(2000)
        assertTrue(echo.isEcho("今天想聊什么", 5500, playing = false))
        assertFalse(echo.isEcho("今天想聊什么", 7000, playing = false))
    }

    @Test fun previousInterruptedRunDoesNotSpoilNewTurnsReply() {
        val list = listOf(UserMessageUi("old-u", "旧"), AgentMessageUi("old-a", "正在说"),
            SystemNoticeMessageUi("stop", SystemNoticeCode.Stopped), UserMessageUi("new-u", "换个话题"),
            AgentMessageUi("new-a", "好，我们聊音乐。"))
        assertEquals("new-a", voiceConversationReply(list, setOf("old-u", "old-a"))?.id)
    }

    @Test fun autoReadPlaysFastReplyEvenIfNoStreamingMarkerWasObservedAndNeverReplaysIt() {
        val history = listOf(UserMessageUi("u1", "旧问题"), AgentMessageUi("a1", "旧回答"))
        val tracker = ChatAutoReadTracker(history.mapTo(mutableSetOf()) { it.id })
        assertNull(tracker.update(history, streaming = false))
        val complete = history + listOf(UserMessageUi("u2", "新问题"), AgentMessageUi("a2", "新回答"))
        assertEquals("a2", tracker.update(complete, streaming = false)?.id)
        assertNull(tracker.update(complete, streaming = false))
    }

    @Test fun autoReadOmitsPartialCancelledAndFailedTurns() {
        val tracker = ChatAutoReadTracker(emptySet())
        val messages = listOf(UserMessageUi("u", "问题"), AgentMessageUi("a", "正在", isStreaming = true))
        assertNull(tracker.update(messages, streaming = true))
        assertNull(tracker.update(listOf(messages.first(), AgentMessageUi("a", "正在"),
            SystemNoticeMessageUi("stop", SystemNoticeCode.Stopped)), streaming = false))
    }
}
