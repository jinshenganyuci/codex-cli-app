package com.nanci.codexremote.test

import com.nanci.codexremote.ui.shell.ComposerPrimaryAction
import com.nanci.codexremote.ui.shell.ComposerState
import com.nanci.codexremote.ui.shell.ShellAttachment
import com.nanci.codexremote.ui.shell.composerCanSend
import com.nanci.codexremote.ui.shell.composerPrimaryAction

/** Exercises the decisions used by the production Composer; no chat or server is created. */
object ShellFixChecks {
    fun run(): List<String> {
        val passed = mutableListOf<String>()
        fun test(name: String, block: () -> Unit) { block(); passed += name }
        val attachment = ShellAttachment("fixture-file", "fixture.png")
        test("running_attachment_only_draft_sends_instead_of_stopping") {
            val state = ComposerState(running = true, attachments = listOf(attachment))
            check(composerPrimaryAction(state) == ComposerPrimaryAction.Send && composerCanSend(state))
        }
        test("running_blank_draft_stops_and_idle_blank_draft_has_no_send") {
            check(composerPrimaryAction(ComposerState(running = true, text = "  ")) == ComposerPrimaryAction.Stop)
            check(composerPrimaryAction(ComposerState(text = "  ")) == ComposerPrimaryAction.Idle)
        }
        test("uploading_or_failed_attachment_does_not_turn_send_into_stop") {
            for (file in listOf(attachment.copy(loading = true), attachment.copy(error = "fixture error"))) {
                val state = ComposerState(running = true, attachments = listOf(file))
                check(composerPrimaryAction(state) == ComposerPrimaryAction.Send && !composerCanSend(state))
            }
        }
        test("submission_disables_duplicate_send_even_with_attachments") {
            val state = ComposerState(submitting = true, running = true, attachments = listOf(attachment))
            check(composerPrimaryAction(state) == ComposerPrimaryAction.Submitting && !composerCanSend(state))
            check(!composerCanSend(ComposerState(enabled = false, attachments = listOf(attachment))))
        }
        return passed
    }
}
