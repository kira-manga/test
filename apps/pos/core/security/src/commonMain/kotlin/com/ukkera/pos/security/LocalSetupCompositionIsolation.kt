package com.ukkera.pos.security

/**
 * E3-007 original setup-composition mode.
 *
 * The mode is selected once, at original composition creation, and is bound to its own native
 * profile (root, keys, SDK identity, launch/intent namespaces and client publication). It is not
 * a mutable flag on an existing composition. LIVE keeps the existing production namespaces
 * unchanged; TRAINING uses separate versioned namespaces and a separate official SDK identity.
 */
public enum class LocalSetupCompositionMode {
    /** Clearly labelled synthetic training composition; never a live authority or production fixture. */
    TRAINING,

    /** The existing production live owner; never created by a training-mode entry. */
    LIVE,
}

/**
 * Bounded training composition slots. Each slot resolves to an independent native root, key
 * custody namespace, launch/intent namespace and SDK identity. A slot is not an arbitrary path
 * or a mode flag on an existing composition; it exists so a second training composition can be
 * created through the same supported setup path.
 */
public enum class LocalTrainingCompositionSlot {
    TRAINING_1,
    TRAINING_2,
}
