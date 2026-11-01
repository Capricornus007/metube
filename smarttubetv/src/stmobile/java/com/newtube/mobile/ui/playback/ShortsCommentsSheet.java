package com.newtube.mobile.ui.playback;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.FragmentActivity;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * METUBE(shorts): the watch page's comments panel, opened over the in-place Shorts host.
 *
 * <p>While a short is hosted the playback Activity is off the view stack, so its own comments
 * panel - like its menu - would be drawn into an invisible window. The panel itself is a plain
 * view, so the host's Activity inflates its own copy and is fed the continuation keys the player
 * page already loaded. Everything else (list, replies, composer, sort) is the same component.</p>
 */
public final class ShortsCommentsSheet {
    private ShortsCommentsSheet() {
    }

    public static void show(@NonNull FragmentActivity activity, @NonNull ConstraintLayout root,
            @NonNull String videoId, @NonNull String topKey, @Nullable String newestKey,
            @Nullable String count) {
        View inflated = LayoutInflater.from(activity)
                .inflate(R.layout.mobile_comments_panel, root, false);
        if (!(inflated instanceof CommentsPanelLayout)) {
            return;
        }
        CommentsPanelLayout layout = (CommentsPanelLayout) inflated;
        ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT);
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        root.addView(layout, lp);

        Sheet sheet = new Sheet(root, layout);
        CommentsPanel panel = new CommentsPanel(activity, layout, sheet);
        sheet.mPanel = panel;
        panel.setSource(videoId, topKey, newestKey, count);
        layout.post(() -> {
            if (layout.isAttachedToWindow()) {
                panel.open();
            }
        });
    }

    private static final class Sheet implements CommentsPanel.Host {
        private final ViewGroup mRoot;
        private final CommentsPanelLayout mLayout;
        @Nullable
        private CommentsPanel mPanel;
        private boolean mReleased;

        Sheet(ViewGroup root, CommentsPanelLayout layout) {
            mRoot = root;
            mLayout = layout;
        }

        @Override
        public void onCommentTimestamp(long positionMs) {
            MiniPlayerBridge.seekShortTo(positionMs);
        }

        @Override
        public void onCommentVideoLink(String videoId) {
            MiniPlayerBridge.openShortsVideo(videoId);
        }

        @Override
        public void onCommentsPanelShown(boolean shown) {
            if (!shown) {
                release();
            }
        }

        /** The panel slid away: detach it and drop its back callback / in-flight requests. */
        private void release() {
            if (mReleased) {
                return;
            }
            mReleased = true;
            mRoot.post(() -> {
                mRoot.removeView(mLayout);
                if (mPanel != null) {
                    mPanel.release();
                }
            });
        }
    }
}
