package dev.dunno.emula

/** Process-local activation state. Active cards are intentionally not restored after a restart. */
internal object CardSession {
    private var processSeen = false
    private var activeCardId: String? = null

    @JvmStatic
    @Synchronized
    fun beginProcess(): Boolean {
        val firstProcessStart = !processSeen
        processSeen = true
        return firstProcessStart
    }

    @JvmStatic
    @Synchronized
    fun getActiveCardId(): String? = activeCardId

    @JvmStatic
    @Synchronized
    fun isActive(cardId: String?): Boolean = cardId != null && cardId == activeCardId

    @JvmStatic
    @Synchronized
    fun setActiveCardId(cardId: String?) {
        activeCardId = cardId
    }

    @JvmStatic
    @Synchronized
    fun clearActiveCard() {
        activeCardId = null
    }
}
