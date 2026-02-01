package com.afterlands.afterlanguage.api.model;

/**
 * State of a translation in a specific language.
 */
public enum TranslationState {
    /**
     * Translation exists and is up-to-date with source.
     */
    TRANSLATED,

    /**
     * No translation exists, using fallback.
     */
    PENDING,

    /**
     * Translation exists but source has changed (hash mismatch).
     */
    OUTDATED
}
