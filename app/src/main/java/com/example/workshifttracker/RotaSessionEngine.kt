package com.example.workshifttracker

/**
 * The authoritative rules for merging a live photo scan with explicit review decisions.
 * This pure Kotlin state machine can be tested using a deliberately slow/late fake scanner
 * without Compose, Android or OCR dependencies.  A user decision always outranks an analyzer.
 */
internal object RotaSessionEngine {
    enum class Stage { PREVIEW, PAGE_OCR, CONTRAST_OCR, THRESHOLD_OCR, HEADER, TIMES, VERIFY, COMPLETE, STOPPED, ERROR }
    enum class Provenance { ANALYZER, USER }

    data class Candidate(
        val id: String,
        val day: Int, // weekday 0..6, independent of guessed year/week
        val slot: String, // stable physical block ID when known, otherwise a normalized time key
        val provenance: Provenance,
        val selected: Boolean = false,
        val userRevision: Long = 0
    )

    data class State(
        val session: String,
        val generation: Int,
        val stage: Stage = Stage.PREVIEW,
        val revision: Long = 0,
        val candidates: List<Candidate> = emptyList(),
        val touchedDays: Set<Int> = emptySet(),
        val deletedSlots: Set<String> = emptySet(),
        val stopAllSuggestions: Boolean = false,
        val weekConfirmed: Boolean = false
    )

    fun begin(session: String, generation: Int): State = State(session = session, generation = generation)

    fun accepts(state: State, session: String, generation: Int): Boolean =
        state.session == session && state.generation == generation && state.stage !in
            setOf(Stage.STOPPED, Stage.ERROR, Stage.COMPLETE)

    fun progress(state: State, session: String, generation: Int, stage: Stage): State =
        if (!accepts(state, session, generation) || state.stage == Stage.COMPLETE) state
        else if (stage.ordinal < state.stage.ordinal && stage !in setOf(Stage.STOPPED, Stage.ERROR)) state
        else state.copy(stage = stage)

    /** Multiple candidates on one day are legitimate. Only identical slot keys are duplicates. */
    fun merge(state: State, session: String, generation: Int, suggestions: List<Candidate>): State {
        if (!accepts(state, session, generation) || state.stopAllSuggestions) return state
        val occupied = state.candidates.mapTo(hashSetOf(), Candidate::slot)
        val additions = suggestions.filter { candidate ->
            candidate.provenance == Provenance.ANALYZER && candidate.day in 0..6 &&
                candidate.day !in state.touchedDays && candidate.slot !in state.deletedSlots &&
                occupied.add(candidate.slot)
        }.map { it.copy(selected = false, userRevision = 0) }
        return state.copy(candidates = state.candidates + additions)
    }

    fun decide(state: State, candidate: Candidate): State {
        require(candidate.day in 0..6)
        val revision = state.revision + 1
        val updated = candidate.copy(provenance = Provenance.USER, userRevision = revision)
        return state.copy(
            revision = revision,
            candidates = state.candidates.filterNot { it.id == candidate.id || it.slot == candidate.slot } + updated,
            touchedDays = state.touchedDays + candidate.day,
            deletedSlots = state.deletedSlots - candidate.slot
        )
    }

    fun remove(state: State, id: String): State {
        val old = state.candidates.firstOrNull { it.id == id } ?: return state
        return state.copy(
            revision = state.revision + 1,
            candidates = state.candidates.filterNot { it.id == id },
            touchedDays = state.touchedDays + old.day,
            deletedSlots = state.deletedSlots + old.slot
        )
    }

    fun select(state: State, id: String, checked: Boolean): State {
        if (state.candidates.none { it.id == id }) return state
        return state.copy(revision = state.revision + 1,
            candidates = state.candidates.map { if (it.id == id) it.copy(selected = checked) else it })
    }

    fun confirmWeek(state: State): State = state.copy(weekConfirmed = true, revision = state.revision + 1)

    fun stop(state: State): State = state.copy(stage = Stage.STOPPED, revision = state.revision + 1)

    fun removeAll(state: State): State = state.copy(
        revision = state.revision + 1,
        touchedDays = (0..6).toSet(),
        deletedSlots = state.deletedSlots + state.candidates.map { it.slot },
        candidates = emptyList(),
        stopAllSuggestions = true
    )
}
