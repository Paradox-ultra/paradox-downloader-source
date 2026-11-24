package bot.paradox.downloader;

import androidx.recyclerview.widget.DefaultItemAnimator;

/** Animates adds/removes, but never cross-fades a card on a progress update. */
final class SmoothAnimator extends DefaultItemAnimator {
    SmoothAnimator() {
        setSupportsChangeAnimations(false);
        setAddDuration(180);
        setRemoveDuration(150);
        setMoveDuration(200);
    }
}
