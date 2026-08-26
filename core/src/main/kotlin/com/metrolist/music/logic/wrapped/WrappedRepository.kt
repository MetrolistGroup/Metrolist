/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.logic.wrapped

data class MessagePair(val range: LongRange, val tease: String, val reveal: String)

object WrappedRepository {
    private val messages = listOf(
        MessagePair(0L..999L, "I really hope you are not dissapointed...", "That's **%d minutes**. Just warming up?"),
        MessagePair(0L..999L, "Testing the waters, are we?", "**%d minutes** is a quick dip in the musical ocean."),
        MessagePair(0L..999L, "Busy schedule this year?", "**%d minutes** is short, sweet, and to the point."),
        MessagePair(0L..999L, "Silence is golden, they say...", "But you preferred **%d minutes** of noise."),

        MessagePair(1000L..4999L, "It seems like you found Metrolist recently...", "And you dedicated **%d minutes** to the tunes."),
        MessagePair(1000L..4999L, "You have a life outside of music.", "**%d minutes** is a healthy balance. We respect that."),
        MessagePair(1000L..4999L, "Not too quiet, not too loud.", "Just the right amount of vibes for **%d minutes**."),
        MessagePair(1000L..4999L, "A casual stop on your journey.", "Thanks for dropping by for **%d minutes**."),

        MessagePair(5000L..14999L, "Music is definitely your thing.", "**%d minutes** is a solid soundtrack for your year."),
        MessagePair(5000L..14999L, "We saw you here quite a bit.", "Always setting the mood for **%d minutes**."),
        MessagePair(5000L..14999L, "Your commute must be fun.", "**%d minutes** of melodies."),
        MessagePair(5000L..14999L, "Consistent. Reliable. Rhythmic.", "You know what you like, for **%d minutes**."),

        MessagePair(15000L..39999L, "Do you ever take your headphones off?", "**%d minutes** suggests music is your oxygen."),
        MessagePair(15000L..39999L, "Your battery is begging for mercy.", "But your ears absolutely love those **%d minutes**."),
        MessagePair(15000L..39999L, "Main Character Energy detected.", "Your life was a movie for **%d minutes**."),
        MessagePair(15000L..39999L, "Walking, working, sleeping...", "There was always a song playing during those **%d minutes**."),

        MessagePair(40000L..Long.MAX_VALUE, "Are you... okay?", "You literally lived here for **%d minutes**."),
        MessagePair(40000L..Long.MAX_VALUE, "We are worried about your eardrums.", "Top 1% behavior. **%d minutes** is legendary."),
        MessagePair(40000L..Long.MAX_VALUE, "Silence scares you, doesn't it?", "A wall of sound, all year long, for **%d minutes**."),
        MessagePair(40000L..Long.MAX_VALUE, "Certified Stress Tester.", "You made those extractors work overtime for **%d minutes**.")
    )

    fun getMessage(minutes: Long): MessagePair {
        val possibleMessages = messages.filter { minutes in it.range }
        val chosenMessage = if (possibleMessages.isNotEmpty()) {
            possibleMessages.random()
        } else {
            // Fallback for safety
            MessagePair(0L..Long.MAX_VALUE, "Looks like we lost count!", "But you definitely listened to **%d minutes** of music.")
        }
        return chosenMessage.copy(
            reveal = chosenMessage.reveal.format(minutes)
        )
    }
}
