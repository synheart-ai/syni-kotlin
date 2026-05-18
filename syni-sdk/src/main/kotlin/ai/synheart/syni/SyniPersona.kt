package ai.synheart.syni

/**
 * A declarative Syni persona — voice, goals, boundaries, output schema.
 *
 * Mirrors `SyniPersona` from `package:syni`. This is the generic type only;
 * `syni-kotlin` defines no concrete personas — that is a product / spec
 * concern. Personas are either supplied by the host app or loaded from
 * bundled `syni-spec` assets via [SyniSpecPersona].
 */
data class SyniPersona(
    /**
     * Canonical spec persona id, e.g. `focus.coach.v1`. Matches the `id`
     * field of the matching JSON file under
     * `syni-spec/personas/{prod,research}/`.
     */
    val id: String,

    /** Human-readable name surfaced in host-app UI. */
    val displayName: String,

    /** System prompt prepended to the HSI-conditioned prompt by the runtime. */
    val systemPrompt: String,

    /**
     * Output schema selector — must be one of the runtime's `OutputSchema`
     * enum values: `suggestions`, `coach`, or `chat`.
     */
    val responseSchemaId: String,

    /** One- or two-sentence tone descriptor. */
    val tone: String = "calm",
)
