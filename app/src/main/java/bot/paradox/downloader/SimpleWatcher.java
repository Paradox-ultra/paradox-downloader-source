package bot.paradox.downloader;

import android.text.Editable;
import android.text.TextWatcher;

/** TextWatcher that only cares about the text after a change. */
final class SimpleWatcher implements TextWatcher {
    interface OnText {
        void changed(CharSequence s);
    }

    private final OnText cb;

    SimpleWatcher(OnText cb) {
        this.cb = cb;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        cb.changed(s);
    }
}
