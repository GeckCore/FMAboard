package org.futo.inputmethod.latin.fmaboard

import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/** Opens "Crear sticker"; the sticker made there is sent into this chat when you come back. */
val StickerAction = Action(
    icon = R.drawable.fmaboard_sticker,
    name = R.string.fmaboard_sticker_title,
    simplePressImpl = { manager, _ ->
        StickerSend.launch(manager.getContext(),
            manager.getLatinIMEForDebug().currentInputEditorInfo?.packageName)
    },
    persistentState = null,
    windowImpl = null,
)
