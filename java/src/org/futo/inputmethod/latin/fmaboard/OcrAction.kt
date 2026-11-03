package org.futo.inputmethod.latin.fmaboard

import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/** Opens "Texto de imagen"; what is grabbed there is typed back into this field. */
val OcrAction = Action(
    icon = R.drawable.fmaboard_scan_text,
    name = R.string.fmaboard_ocr_title,
    simplePressImpl = { manager, _ ->
        OcrCapture.launch(manager.getContext(),
            manager.getLatinIMEForDebug().currentInputEditorInfo?.packageName)
    },
    persistentState = null,
    windowImpl = null,
)
