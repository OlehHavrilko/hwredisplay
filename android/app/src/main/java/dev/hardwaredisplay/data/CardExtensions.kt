package dev.hardwaredisplay.data

val CardEntity.roleEnum: CardRole
    get() = runCatching { CardRole.valueOf(role) }.getOrDefault(CardRole.SECONDARY)

val CardEntity.viewEnum: CardView
    get() = runCatching { CardView.valueOf(view) }.getOrDefault(CardView.NUMBER)
