package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Burst;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.Motion;

import java.util.List;

/**
 * METUBE(shorts): the Shorts tab played IN PLACE, on the screen that owns the tab.
 *
 * <p>The playback Activity keeps ExoPlayer, the media session, the queue and the notification; it
 * hides its own window behind the host (see MobilePlaybackActivity#minimizeIntoShortsHost) and this
 * class draws the session's {@link SurfaceTexture} full screen - the same trick
 * {@link MobileMiniPlayerController} uses for the corner card, at the size
 * {@code animateFromWatchPage} already proves the texture can be drawn at. The codec's output
 * surface never changes, so nothing re-inits and nothing stalls, and the user never leaves the page
 * they tapped. Paging forwards to the player's existing queue navigation through
 * {@link MiniPlayerBridge}.</p>
 *
 * <p>Two rules make that safe, both inherited from the card path: one SurfaceTexture feeds one GL
 * consumer at a time, so the host takes the texture only while it is the visible screen and hands it
 * back (frozen on its last frame) before it pauses; and while the host is up the mini card must stay
 * hidden, or the two views fight over that texture.</p>
 *
 * <p>The vertical drag moves the whole page 1:1 with the finger and slides the neighbour's portrait
 * into the gap it opens - the same image the new short is then covered with until its first real
 * frame arrives, as the official pager does. With nothing queued in that direction the drag only
 * rubber-bands.</p>
 *
 * <p>METUBE(shorts) 8c: this is the ONLY Shorts surface. The full-screen {@code ShortsOverlay} that
 * used to duplicate it inside the playback Activity is deleted, and the three things only it did are
 * done here: the neighbour portraits are warmed in Glide's memory cache as a short opens
 * ({@link #preloadNeighbours}), a double tap likes with a popped heart ({@link #onTapUp},
 * {@link #popHeart}), and a like made here splashes on this rail ({@link #onShortsActionsChanged}).
 * A short opened anywhere else (search, a channel tab, a link) is an ordinary watch-page video, the
 * way it already was for those screens.</p>
 */
public final class MobileShortsHost {
    /** What the hosting screen still has to do for this player. */
    public interface Callback {
        /** The queue is nearly out: ask the section's presenter for another page. */
        void onShortsHostNeedMore();
    }

    private static final long TICK_MS = 500;
    private static final long OUT_MS = 220;
    private static final long IN_MS = 240;
    private static final float FLING_PX_PER_S = 1200f;
    /** No neighbour to page to: the drag gives a quarter of the finger's travel. */
    private static final float RUBBER_BAND = 0.25f;
    /** The cover (the neighbour's portrait / the loading still) never holds the picture longer. */
    private static final long COVER_MAX_MS = 2500;
    /** A frame this soon after a page turn is still the OLD stream's queued buffer, not the new one. */
    private static final long COVER_SETTLE_MS = 350;
    /**
     * METUBE(shorts): at once - a swipe inside the old 1.2 s window found no neighbour warmed (the
     * value and the finding come from the deleted ShortsOverlay). The portraits are small next to the
     * short's own media chunks.
     */
    private static final long NEIGHBOUR_PRELOAD_DELAY_MS = 0;
    /** The steps whose portraits are warmed: the reachable neighbours plus two pages ahead. */
    private static final int[] PRELOAD_STEPS = {1, -1, 2, 3};
    /** oar2 is asked for at the short's own shape, never at the view's (Glide downsamples to this). */
    private static final int PORTRAIT_W = 720;
    private static final int PORTRAIT_H = 1280;

    private final Activity mActivity;
    private final Callback mCallback;
    private final View mHost;
    private final View mPage;
    private final FrameLayout mFrame;
    private final ImageView mStill;
    private final ImageView mPeek;
    private final ImageView mFlash;
    private final ImageView mHeart;
    private final LinearLayout mInfo;
    private final TextView mChannel;
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mDescription;
    private final LinearLayout mActions;
    private final ImageView mLikeIcon;
    private final ImageView mDislikeIcon;
    private final ImageView mDisc;
    private final TextView mLikeCount;
    private final TextView mCommentsCount;
    private final ProgressBar mProgress;
    private final Runnable mTick = this::onTick;
    private final Runnable mCoverTimeout = this::liftCover;
    private final Runnable mPreloadNeighbours = this::preloadNeighbours;
    private final int mTouchSlop;

    @Nullable
    private TextureView mTexture;
    @Nullable
    private String mShownVideoId;
    @Nullable
    private Video mPeekVideo;
    private int mPeekDir;
    private boolean mCoverArmed;
    private boolean mCoverReady;
    private long mCoverArmedAt;
    private boolean mDescriptionExpanded;
    /** close()'s fade is running: the session-end callback must not cut it short. */
    private boolean mClosing;
    /** The last pushed like state, so a like made here can splash exactly once. */
    private boolean mWasLiked;
    /** The last pushed dislike state, for the same one-shot reason. */
    private boolean mWasDisliked;
    /** The tap waits this long for a possible second tap: one pauses, two like (see {@link #onTapUp}). */
    private final int mDoubleTapTimeoutMs;
    private long mLastTapAt;
    private float mLastTapX;
    private float mLastTapY;
    private final Runnable mSingleTap = this::togglePlayPause;

    // Touch / drag state.
    private float mDownY;
    private float mDownX;
    private boolean mDragging;
    private boolean mDragged;
    /** The thin line at the bottom is the scrub bar: a drag that starts in its band seeks. */
    private boolean mScrubbing;
    private long mDurationMs;
    private final int mScrubBandPx;
    /** METUBE(shorts): how far the line (and the band that owns it) sits off the bottom edge. */
    private final int mProgressInsetPx;
    /** The line's measured distance from the host's bottom edge, inset included; 0 until laid out. */
    private int mProgressBottomPx;
    private int mVideoBottomInsetPx;
    private int mLastVideoW;
    private int mLastVideoH;
    private int mLastVideoRot;
    /** px from the bottom of the host to the floating nav capsule (measured, not assumed). */
    private int mPillInsetPx;
    @Nullable
    private VelocityTracker mTracker;

    public MobileShortsHost(@NonNull Activity activity, @NonNull Callback callback) {
        mActivity = activity;
        mCallback = callback;
        mHost = activity.findViewById(R.id.mobile_shorts_host);
        mPage = activity.findViewById(R.id.mobile_shorts_host_page);
        mFrame = activity.findViewById(R.id.mobile_shorts_host_frame);
        mStill = activity.findViewById(R.id.mobile_shorts_host_still);
        mPeek = activity.findViewById(R.id.mobile_shorts_host_peek);
        mFlash = activity.findViewById(R.id.mobile_shorts_host_flash);
        mInfo = activity.findViewById(R.id.mobile_shorts_host_info);
        mChannel = activity.findViewById(R.id.mobile_shorts_host_channel);
        mTitle = activity.findViewById(R.id.mobile_shorts_host_title);
        mMeta = activity.findViewById(R.id.mobile_shorts_host_meta);
        mDescription = activity.findViewById(R.id.mobile_shorts_host_description);
        mActions = activity.findViewById(R.id.mobile_shorts_host_actions);
        mLikeIcon = activity.findViewById(R.id.mobile_shorts_host_like);
        mDislikeIcon = activity.findViewById(R.id.mobile_shorts_host_dislike);
        mDisc = activity.findViewById(R.id.mobile_shorts_host_disc);
        mLikeCount = activity.findViewById(R.id.mobile_shorts_host_like_count);
        mCommentsCount = activity.findViewById(R.id.mobile_shorts_host_comments_count);
        mProgress = activity.findViewById(R.id.mobile_shorts_host_progress);
        mHeart = activity.findViewById(R.id.mobile_shorts_host_heart);
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        // METUBE(shorts) 8a: the scrub band and the line's inset are resources, not arithmetic on a
        // magic density (values/dimens_mobile.xml; values-sw600dp sizes them up for a tablet).
        mScrubBandPx = activity.getResources()
                .getDimensionPixelSize(R.dimen.metube_shorts_scrub_band);
        mProgressInsetPx = activity.getResources()
                .getDimensionPixelSize(R.dimen.metube_shorts_progress_inset);
        mDoubleTapTimeoutMs = ViewConfiguration.get(activity).getDoubleTapTimeout();

        mHost.setOnTouchListener(this::onTouch);
        // A tap on the picture is play/pause, as in the official Shorts. The touch listener above
        // consumes the events, so the click it dispatches for a tap lands here. (A real finger gets
        // the same thing through onTapUp, which is what can tell a tap from a double tap; this
        // listener is what an accessibility "click" and a keyboard activate go through, and they have
        // no second tap to wait for.)
        mHost.setOnClickListener(v -> togglePlayPause());
        // The action rail forwards to the playback Activity's own watch buttons; it keeps the
        // like/subscribe/count state in one place for both surfaces.
        if (mLikeIcon != null) {
            mLikeIcon.setOnClickListener(v -> MiniPlayerBridge.shortsLike());
            mirrorCheckedState(mLikeIcon);
        }
        if (mDislikeIcon != null) {
            mDislikeIcon.setOnClickListener(v -> MiniPlayerBridge.shortsDislike());
            mirrorCheckedState(mDislikeIcon);
        }
        // METUBE(shorts) 8d: the labels under the icons repeat their button's action (the count
        // doubles as the "comments" label until the number arrives). They stay clickable - a thumb
        // lands on the text as often as on the glyph - but not focusable, so a D-pad walks the rail
        // once per action instead of stopping twice, and TalkBack does not read "留言" as its own
        // control after it has read the button. Taken by type: the rail alternates ImageButton and
        // TextView, and only the text ones are duplicates.
        if (mActions != null) {
            for (int i = 0; i < mActions.getChildCount(); i++) {
                View child = mActions.getChildAt(i);
                if (child instanceof TextView) {
                    setLabelChrome(child);
                }
            }
        }
        View comments = activity.findViewById(R.id.mobile_shorts_host_comments);
        if (comments != null) {
            comments.setOnClickListener(v -> MiniPlayerBridge.shortsComments());
        }
        // The count doubles as the "comments" label (it shows 留言 until the number arrives), so a
        // tap on it has to do what the icon does. Same for the share label under its icon.
        if (mCommentsCount != null) {
            mCommentsCount.setOnClickListener(v -> MiniPlayerBridge.shortsComments());
        }
        View share = activity.findViewById(R.id.mobile_shorts_host_share);
        if (share != null) {
            share.setOnClickListener(v -> MiniPlayerBridge.shortsShare());
        }
        View shareLabel = activity.findViewById(R.id.mobile_shorts_host_share_label);
        if (shareLabel != null) {
            shareLabel.setOnClickListener(v -> MiniPlayerBridge.shortsShare());
        }
        View more = activity.findViewById(R.id.mobile_shorts_host_more);
        if (more != null) {
            more.setOnClickListener(v -> MiniPlayerBridge.shortsMore());
        }
        // NOTE: the info block deliberately has NO click listener of its own. A clickable child
        // would swallow the DOWN and the vertical swipe would dead-zone over the short's text;
        // instead a tap inside its rect is recognized here (see release()) and opens it.
    }

    /**
     * METUBE(shorts) 8d: a rail label is a second hit area for the icon above it, not a control of
     * its own: focusable off (the icon is the D-pad stop), and its contentDescription stays null so
     * the accessible name comes from the button, not from a bare count.
     */
    private static void setLabelChrome(@Nullable View label) {
        if (label == null) {
            return;
        }
        label.setFocusable(false);
        label.setFocusableInTouchMode(false);
    }

    /**
     * METUBE(shorts) 8d: like / dislike are toggles, so say so to accessibility: TalkBack reads
     * "讚，已選取" instead of announcing the same action again. {@code isSelected()} carries the
     * state (set in {@link #onShortsActionsChanged}); the drawable swap is the sighted half.
     */
    private static void mirrorCheckedState(@NonNull View button) {
        ViewCompat.setAccessibilityDelegate(button, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
    }

    /**
     * The short's own portrait, at its own aspect (oar2). One request shape for the cover, the peek,
     * its preload and the watch page's loading still - moved here verbatim from the deleted
     * {@code ShortsOverlay} so the one Shorts surface owns it.
     *
     * <p>fitCenter, not centerCrop: oar2 is the short's own aspect, and the views showing it crop it
     * into the video's box as the player crops the video - cropped twice, it jumped when the video
     * replaced it.</p>
     */
    public static RequestBuilder<Drawable> portrait(@NonNull Context context, @NonNull String videoId) {
        return Glide.with(context.getApplicationContext())
                .load("https://i.ytimg.com/vi/" + videoId + "/oar2.jpg")
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .override(PORTRAIT_W, PORTRAIT_H)
                .fitCenter();
    }

    /**
     * A short fills a phone's screen (zoomed, YouTube's way), cropping a little off a 9:16 short's
     * sides. A tablet's screen is shaped too unlike a short for that: there a short follows the zoom
     * setting, so Default shows all of it - zoomed, a 4:5 short lost a fifth of its width (#25).
     * Also the watch page's rule for its own loading still of a short (from the deleted overlay).
     */
    public static boolean fillsScreen(@NonNull Context context) {
        return context.getApplicationContext().getResources().getConfiguration().smallestScreenWidthDp < 600;
    }

    public boolean isVisible() {
        return mHost != null && mHost.getVisibility() == View.VISIBLE;
    }

    /**
     * The host is about to become the screen the video is drawn on: show it and adopt the session
     * texture NOW, while the translucent player window is still on top, so the reorder that brings
     * this window forward already has a frame with the picture in it.
     */
    public boolean prepareForHandoff(@NonNull Runnable onDrawn) {
        if (mHost == null || mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        show();
        bindInfo(MiniPlayerBridge.getShortsInfo(), true);
        settleCover();
        // FrameGate waits for a submitted frame of this window (with a fallback for an OEM that
        // suppresses draws), which is what the card's hand-written draw gate does.
        FrameGate.afterNextFrame(mHost, 100, onDrawn);
        return true;
    }

    /** onResume / back from behind another screen: re-adopt the live session if it is still ours. */
    public void sync() {
        if (mHost == null) {
            return;
        }
        if (!MiniPlayerBridge.isFullscreen()) {
            hide();
            return;
        }
        show();
        bindInfo(MiniPlayerBridge.getShortsInfo(), false);
        settleCover();
    }

    private void show() {
        attachTexture();
        if (mHost.getVisibility() != View.VISIBLE) {
            mHost.setVisibility(View.VISIBLE);
        }
        mHost.animate().cancel();
        mHost.setAlpha(1f);
        mPage.setTranslationY(0f);
        mPage.setAlpha(1f);
        syncInfoBottomInset();
        if (mPillInsetPx == 0) {
            // The capsule has not been laid out yet (a cold hand-off draws both in one pass): the
            // tick re-measures it, and the pill height is a constant 68dp until then.
            mHost.post(this::syncInfoBottomInset);
        }
        startTick();
    }

    /**
     * Freeze the last frame and release this window's TextureView. Called when the host pauses (the
     * player may be taking the texture back): the picture stays put as a bitmap until whoever owns
     * the session next hands frames over.
     */
    public void detachTexture() {
        if (mTexture != null && mTexture.getParent() != null) {
            if (mTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap frame = mTexture.getBitmap();
                if (frame != null) {
                    showStillBitmap(frame);
                }
            }
            // No setSurfaceTexture(null) swap: TextureView releases whatever it holds, which would
            // destroy the session texture (see MobilePlaybackActivity#detachVideoTexture).
            mFrame.removeView(mTexture);
            mTexture = null;
        }
    }

    /** Stand down: no texture held, nothing visible, no ticking. */
    public void hide() {
        mClosing = false;
        stopTick();
        detachTexture();
        if (mHost == null) {
            return;
        }
        mHost.removeCallbacks(mCoverTimeout);
        mHost.removeCallbacks(mPreloadNeighbours);
        mHost.removeCallbacks(mSingleTap);
        mLastTapAt = 0L;
        if (mHeart != null) {
            mHeart.animate().cancel();
            mHeart.setAlpha(0f);
        }
        mHost.animate().cancel();
        mCoverArmed = false;
        mCoverReady = false;
        mStill.setAlpha(1f);
        mStill.setVisibility(View.GONE);
        mPeek.setTranslationY(0f);
        mPeek.setVisibility(View.GONE);
        mPeekVideo = null;
        mPeekDir = 0;
        mDragging = false;
        mDragged = false;
        mPage.setTranslationY(0f);
        mPage.setAlpha(1f);
        mHost.setAlpha(1f);
        mHost.setVisibility(View.GONE);
        mShownVideoId = null;
    }

    /**
     * Leaving the in-place player (Back, another tab). {@code onGone} runs once the host is off
     * screen and the texture is back with the player: stopping the hidden session there blocks the
     * main thread for ~60 ms (measured on the card path) and would otherwise eat this fade.
     */
    public void close(@Nullable Runnable onGone) {
        if (!isVisible()) {
            stopTick();
            detachTexture();
            if (onGone != null) {
                onGone.run();
            }
            return;
        }
        stopTick();
        detachTexture();
        mClosing = true;
        mHost.animate().cancel();
        mHost.animate().alpha(0f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE).withLayer()
                .withEndAction(() -> {
                    mClosing = false;
                    hide();
                    if (onGone != null) {
                        onGone.run();
                    }
                }).start();
    }

    // ---------------------------------------------------------------------------------
    // Bridge callbacks
    // ---------------------------------------------------------------------------------

    /** The player pushed this short's text (again, as its counts / description arrive). */
    public void onInfoChanged(@Nullable MiniPlayerBridge.ShortsInfo info) {
        if (!isVisible()) {
            return;
        }
        bindInfo(info, info != null && !equalsId(info.videoId, mShownVideoId));
    }

    /** The new stream reached READY: frames of it can be expected from now on. */
    public void onStreamReady() {
        mCoverReady = true;
    }

    /**
     * The session ended (expanded back to the watch page, closed, or the player died). The host is
     * holding the last frame, and the window taking over is translucent - hiding here at once would
     * flash the feed underneath. Stay up until that window has drawn (with a fallback), then go.
     */
    public void onSessionEnded() {
        if (!isVisible() || mClosing) {
            return; // nothing on screen to fold, or close() is already fading it out
        }
        FrameGate.afterNextFrame(mHost, 120, this::hide);
    }

    // ---------------------------------------------------------------------------------
    // Info block
    // ---------------------------------------------------------------------------------

    private void bindInfo(@Nullable MiniPlayerBridge.ShortsInfo info, boolean fresh) {
        if (info == null) {
            return;
        }
        mChannel.setText(info.channel);
        mTitle.setText(info.title);
        mMeta.setText(info.meta);
        mDescription.setText(info.description);
        mDescription.setVisibility(isEmpty(info.description) ? View.GONE : View.VISIBLE);
        if (fresh) {
            mShownVideoId = info.videoId;
            mDescriptionExpanded = false;
            applyDescriptionLines();
            if (info.videoId != null) {
                armCover(info.videoId);
            }
            // METUBE(shorts): warm Glide's memory cache with the shorts a swipe can reach, so the
            // peek is on screen at once instead of arriving halfway through the slide. Folded here
            // from the deleted ShortsOverlay: the same finding (a preload started 1.2 s after the
            // open never served the next swipe) is why this runs at once.
            mHost.removeCallbacks(mPreloadNeighbours);
            mHost.postDelayed(mPreloadNeighbours, NEIGHBOUR_PRELOAD_DELAY_MS);
        }
    }

    private void preloadNeighbours() {
        if (!isVisible()) {
            return;
        }
        for (int step : PRELOAD_STEPS) {
            Video video = neighbour(step);
            if (video != null && video.videoId != null) {
                portrait(mHost.getContext(), video.videoId).preload();
            }
        }
    }

    /** The browse activity forwards the watch page's rail snapshot (both surfaces share state). */
    public void onShortsActionsChanged(@Nullable MiniPlayerBridge.ShortsActions actions) {
        if (mLikeIcon == null) {
            return;
        }
        boolean liked = actions != null && actions.liked;
        boolean disliked = actions != null && actions.disliked;
        // METUBE(shorts) 8d: three channels for the same state, none of them a guess by the user.
        //  * the glyph: filled while on, outlined while off (what the watch page's own row does);
        //  * isSelected(): the pressed look for anything that styles it (and the value TalkBack
        //    reads through the delegate installed in the constructor);
        //  * the contentDescription: "Like" while off, "Liked" while on (@string/mobile_watch_liked,
        //    the same pair the state uses in every locale).
        // Like and dislike are mutually exclusive because BOTH surfaces forward to the watch page's
        // own buttons (MiniPlayerBridge.shortsLike / shortsDislike) - the InnerTube rating call
        // toggles one and clears the other there, and this rail only mirrors what comes back.
        mLikeIcon.setImageResource(liked
                ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline);
        mLikeIcon.setSelected(liked);
        mLikeIcon.setContentDescription(mActivity.getString(liked
                ? R.string.mobile_watch_liked : R.string.mobile_watch_like));
        if (mDislikeIcon != null) {
            mDislikeIcon.setImageResource(disliked
                    ? R.drawable.ic_watch_thumb_down : R.drawable.ic_watch_thumb_down_outline);
            mDislikeIcon.setSelected(disliked);
            mDislikeIcon.setContentDescription(mActivity.getString(disliked
                    ? R.string.mobile_watch_disliked : R.string.mobile_watch_dislike));
        }
        // METUBE(burst): where a like made on the Shorts page splashes. The deleted ShortsOverlay was
        // the target when the tap came from its own rail; this rail is the Shorts page's rail now, so
        // the splash follows the state change however it was made - this button, a double tap, or the
        // watch page's row after an expand.
        if (liked && !mWasLiked) {
            Burst.play(mLikeIcon, androidx.core.content.ContextCompat.getColor(
                    mActivity, R.color.mobile_color_primary));
        } else if (disliked && !mWasDisliked && mDislikeIcon != null) {
            Motion.pop(mDislikeIcon);
        }
        mWasLiked = liked;
        mWasDisliked = disliked;
        if (mLikeCount != null) {
            mLikeCount.setText(actions != null && !isEmpty(actions.likeCount)
                    ? actions.likeCount : mActivity.getString(R.string.mobile_watch_like));
        }
        if (mCommentsCount != null) {
            mCommentsCount.setText(actions != null && !isEmpty(actions.commentCount)
                    ? actions.commentCount : mActivity.getString(R.string.mobile_comments_title));
        }
        if (mDisc != null) {
            if (actions != null && actions.avatar != null) {
                mDisc.setImageDrawable(actions.avatar);
                mDisc.setVisibility(View.VISIBLE);
            } else {
                mDisc.setVisibility(View.GONE);
            }
        }
    }

    /** The rect the short's text occupies, in the host's own coordinates. */
    private boolean inInfo(float x, float y) {
        if (mInfo == null || mInfo.getWidth() <= 0 || mInfo.getHeight() <= 0) {
            return false;
        }
        int[] at = new int[2];
        int[] here = new int[2];
        mInfo.getLocationInWindow(at);
        mHost.getLocationInWindow(here);
        float left = at[0] - here[0];
        float top = at[1] - here[1];
        return x >= left && x <= left + mInfo.getWidth()
                && y >= top && y <= top + mInfo.getHeight();
    }

    /** Tap: the description opens to its full length (and folds back on a second tap). */
    private void toggleDescription() {
        if (isEmpty(mDescription.getText())) {
            return;
        }
        mDescriptionExpanded = !mDescriptionExpanded;
        applyDescriptionLines();
    }

    /**
     * METUBE(shorts): the complaint this answers - a short's text was cut to one line. Title three
     * lines, meta two, description two, and a tap shows all of it (never a fixed-height clip).
     */
    private void applyDescriptionLines() {
        mDescription.setMaxLines(mDescriptionExpanded ? Integer.MAX_VALUE : 2);
        mMeta.setMaxLines(mDescriptionExpanded ? Integer.MAX_VALUE : 2);
        mTitle.setMaxLines(mDescriptionExpanded ? Integer.MAX_VALUE : 3);
    }

    /**
     * The floating nav capsule overlaps the bottom of the screen: the scrub line sits just above
     * it and the text block just above the line (see {@link #inScrubBand(float)} for why the line
     * needs a band of its own rather than its 2dp).
     */
    private void syncInfoBottomInset() {
        View pill = mActivity.findViewById(R.id.mobile_nav_glass);
        if (mInfo == null) {
            return;
        }
        float density = mActivity.getResources().getDisplayMetrics().density;
        int height = pill != null && pill.getHeight() > 0
                ? pill.getHeight() : Math.round(68 * density);
        mPillInsetPx = height + Math.round(12 * density);
        // ⚠️ 參數型別看的是「父容器」不是「自己」：mobile_shorts_host_info 本身是 LinearLayout，
        //    但它掛在 mobile_shorts_host_page（FrameLayout）底下，所以 getLayoutParams() 回的是
        //    FrameLayout.LayoutParams —— 直接強轉 LinearLayout.LayoutParams 會 ClassCastException
        //    （實測崩潰日誌 app_errors_stacktrace_*.log：android.widget.FrameLayout$LayoutParams
        //    cannot be cast to android.widget.LinearLayout$LayoutParams，打開 Shorts 必閃退）。
        //    兩者的共同父類是 MarginLayoutParams，bottomMargin 就定義在那裡。
        ViewGroup.LayoutParams raw = mInfo.getLayoutParams();
        int infoBottom = mPillInsetPx + mScrubBandPx;
        if (raw instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams) raw).bottomMargin != infoBottom) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
            lp.bottomMargin = infoBottom;
            mInfo.setLayoutParams(lp);
        }
        // METUBE(shorts) 8a: the line never sits flush with the screen's bottom edge. Whatever the
        // geometry lands on (the floating capsule, or the picture's own bottom edge when the short is
        // letterboxed inside the viewport), @dimen/metube_shorts_progress_inset is added to it, so a
        // line at the very bottom - which reads as part of the system bar and is the first thing a
        // thumb covers - is a thing the user can see and aim at.
        if (mProgress != null && mProgress.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) mProgress.getLayoutParams();
            int progressBottom = Math.max(mPillInsetPx, mVideoBottomInsetPx) + mProgressInsetPx;
            mProgressBottomPx = progressBottom;
            if (pp.bottomMargin != progressBottom) {
                pp.bottomMargin = progressBottom;
                mProgress.setLayoutParams(pp);
            }
        }
        // Keep the action rail above the floating nav capsule, on the same line as the text.
        if (mActions != null
                && mActions.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams ap =
                    (ViewGroup.MarginLayoutParams) mActions.getLayoutParams();
            if (ap.bottomMargin != infoBottom) {
                ap.bottomMargin = infoBottom;
                mActions.setLayoutParams(ap);
            }
        }
    }

    /** The strip the horizontal scrub drag is read from: the band the line lives in. */
    private boolean inScrubBand(float y) {
        // The band belongs to the LINE, not to the screen edge: it moves with the inset above, or
        // 8a would have lifted the picture out of the gesture's reach.
        float bottom = mHost.getHeight() - mProgressBottomPx;
        return mDurationMs > 0 && mProgressBottomPx > 0
                && y >= bottom - mScrubBandPx && y <= bottom;
    }

    // ---------------------------------------------------------------------------------
    // The cover: the portrait shown until the live stream is actually on screen
    // ---------------------------------------------------------------------------------

    private void armCover(@NonNull String videoId) {
        mCoverArmed = true;
        mCoverReady = false;
        mCoverArmedAt = android.os.SystemClock.elapsedRealtime();
        mStill.animate().cancel();
        mStill.setAlpha(1f);
        mStill.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait(mActivity, videoId).into(mStill);
        mStill.setVisibility(View.VISIBLE);
        mHost.removeCallbacks(mCoverTimeout);
        mHost.postDelayed(mCoverTimeout, COVER_MAX_MS);
    }

    private void showStillBitmap(@NonNull Bitmap frame) {
        mCoverArmed = false;
        mCoverReady = false;
        mHost.removeCallbacks(mCoverTimeout);
        mStill.animate().cancel();
        mStill.setAlpha(1f);
        mStill.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mStill.setImageBitmap(frame);
        mStill.setVisibility(View.VISIBLE);
    }

    /**
     * After the texture is adopted and the info bound: make sure whatever is covering the picture
     * is guaranteed to come off again. A frozen frame left by the last {@link #detachTexture()} has
     * no cover flag, so it gets one (lifted by the first live update); nothing at all over a fresh
     * texture means the new short's portrait goes up until its first frame.
     */
    private void settleCover() {
        if (mStill.getVisibility() == View.VISIBLE && !mCoverArmed) {
            mCoverArmed = true;
            mCoverReady = true; // the session texture is live again: any update is a real frame
            mCoverArmedAt = android.os.SystemClock.elapsedRealtime();
            mHost.removeCallbacks(mCoverTimeout);
            mHost.postDelayed(mCoverTimeout, COVER_MAX_MS);
        } else if (mStill.getVisibility() != View.VISIBLE && mShownVideoId != null) {
            armCover(mShownVideoId);
        }
    }

    /** A frame of the new short is on the texture: the cover goes. */
    private void liftCover() {
        if (!mCoverArmed || mTexture == null) {
            return; // nothing waiting to be lifted, or no live surface to lift it for
        }
        mCoverArmed = false;
        mCoverReady = false;
        mHost.removeCallbacks(mCoverTimeout);
        mStill.animate().cancel();
        mStill.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS).withEndAction(() -> {
            mStill.setAlpha(1f);
            mStill.setVisibility(View.GONE);
            mStill.setImageDrawable(null);
        }).start();
        hidePeek();
    }

    // ---------------------------------------------------------------------------------
    // Texture ownership (the rules MobileMiniPlayerController already uses)
    // ---------------------------------------------------------------------------------

    private void attachTexture() {
        if (mTexture == null) {
            final TextureView texture = new TextureView(mActivity);
            texture.setOpaque(false); // NEWTUBE(texture-opaque): see MobilePlaybackActivity
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture created, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (session != null && created != session) {
                        texture.setSurfaceTexture(session);
                        created.release();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    MiniPlayerBridge.setFullscreenViewport(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture destroyed) {
                    // The playback Activity owns the session texture: release only a temporary
                    // surface this view created and never swapped out.
                    return destroyed != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    if (mCoverArmed) {
                        long age = android.os.SystemClock.elapsedRealtime() - mCoverArmedAt;
                        // Either the player says its new stream is READY, or enough time has passed
                        // that this cannot be a leftover buffer of the previous short any more.
                        if (mCoverReady || age > COVER_SETTLE_MS) {
                            liftCover();
                        }
                    }
                }
            });
            mTexture = texture;
        }
        if (mTexture.getParent() == null) {
            mFrame.addView(mTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            MiniPlayerBridge.setFullscreenViewport(mFrame.getWidth(), mFrame.getHeight());
        }
    }

    // ---------------------------------------------------------------------------------
    // Touch: the vertical pager, and a tap for play/pause
    // ---------------------------------------------------------------------------------

    private boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = event.getY();
                mDownX = event.getX();
                mDragging = false;
                mDragged = false;
                // METUBE(shorts): the band the scrub line lives in belongs to it, not to the pager.
                mScrubbing = inScrubBand(mDownY);
                if (mScrubbing) {
                    scrubTo(event.getX());
                    return true;
                }
                recycleTracker();
                mTracker = VelocityTracker.obtain();
                mTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mScrubbing) {
                    scrubTo(event.getX());
                    return true;
                }
                if (mTracker == null) {
                    return true;
                }
                mTracker.addMovement(event);
                float dy = event.getY() - mDownY;
                if (!mDragging) {
                    if (Math.abs(dy) > mTouchSlop && Math.abs(dy) >= Math.abs(event.getX() - mDownX)) {
                        mDragging = true;
                    } else {
                        return true;
                    }
                }
                drag(dy);
                return true;
            case MotionEvent.ACTION_UP:
                if (mScrubbing) {
                    mScrubbing = false;
                    MiniPlayerBridge.seekShortTo(mPendingScrubMs);
                    return true;
                }
                release(travelTo(event), velocity(event), false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                mScrubbing = false;
                release(travelTo(event), 0f, true);
                return true;
            default:
                return false;
        }
    }

    private float velocity(@NonNull MotionEvent up) {
        if (mTracker == null) {
            return 0f;
        }
        mTracker.addMovement(up);
        mTracker.computeCurrentVelocity(1000);
        return mTracker.getYVelocity();
    }

    private void recycleTracker() {
        if (mTracker != null) {
            mTracker.recycle();
            mTracker = null;
        }
    }

    /** The short follows the finger; the neighbour's portrait comes in behind it. */
    private void drag(float dy) {
        mDragged = true;
        int dir = dy < 0 ? 1 : dy > 0 ? -1 : 0; // finger up: the next short comes up from below
        if (dir != mPeekDir) {
            showPeek(dir);
        }
        mPage.animate().cancel();
        mPage.setTranslationY(mPeekVideo != null ? dy : dy * RUBBER_BAND);
        if (mPeekVideo != null) {
            float height = Math.max(1, mPage.getHeight());
            mPeek.setTranslationY((dir > 0 ? height : -height) + mPage.getTranslationY());
        }
    }

    /** Where the finger is on the scrub line, remembered until the finger lifts. */
    private long mPendingScrubMs;

    private void scrubTo(float x) {
        if (mDurationMs <= 0 || mProgress.getWidth() <= 0) {
            return;
        }
        float fraction = Math.max(0f, Math.min(1f, x / mProgress.getWidth()));
        mPendingScrubMs = (long) (fraction * mDurationMs);
        mProgress.setProgress(Math.round(fraction * 1000));
    }

    private float travelTo(@NonNull MotionEvent event) {
        return event.getY() - mDownY;
    }

    private void release(float dy, float yVelocity, boolean cancelled) {
        recycleTracker();
        float height = Math.max(1, mPage.getHeight());
        boolean far = Math.abs(dy) > height * 0.18f;
        boolean fling = Math.abs(yVelocity) > FLING_PX_PER_S
                && Math.signum(yVelocity) == Math.signum(dy);
        if (!mDragged) {
            if (cancelled) {
                mHost.removeCallbacks(mSingleTap);
                mLastTapAt = 0L;
                return;
            }
            if (inInfo(mDownX, mDownY)) {
                toggleDescription(); // a tap on the text opens it, it is not a play/pause
            } else {
                onTapUp(); // a tap on the picture: pause, unless it turns out to be a double
            }
            return;
        }
        mDragged = false;
        if (mPeekVideo == null || !(far || fling)) {
            mPage.animate().translationY(0f).setDuration(IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(this::hidePeek).start();
            return;
        }
        boolean next = dy < 0;
        Haptics.tick(mPage);
        float left = 1f - Math.min(1f, Math.abs(mPage.getTranslationY()) / height);
        mPage.animate().translationY(next ? -height : height)
                .setDuration(Math.max(90, (long) (OUT_MS * left)))
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    mProgress.setProgress(0);
                    // The neighbour's portrait now fills the screen: it stays there, over the old
                    // short's last frame, until the new short's first frame lands (or COVER_MAX_MS).
                    mPeek.setTranslationY(0f);
                    mPeekDir = 0;
                    mPage.setTranslationY(0f);
                    if (next) {
                        MiniPlayerBridge.playNextShort();
                        Video current = MiniPlayerBridge.getVideo();
                        List<Video> queue = Playlist.instance().getAll();
                        if (current != null && queue.indexOf(current) >= queue.size() - 3) {
                            mCallback.onShortsHostNeedMore();
                        }
                    } else {
                        MiniPlayerBridge.playPreviousShort();
                    }
                })
                .start();
    }

    private void showPeek(int dir) {
        mPeekDir = dir;
        mPeekVideo = dir != 0 ? neighbour(dir) : null;
        if (mPeekVideo == null || mPeekVideo.videoId == null) {
            mPeekVideo = null;
            mPeek.setVisibility(View.GONE);
            return;
        }
        float height = Math.max(1, mPage.getHeight());
        mPeek.setTranslationY(dir > 0 ? height : -height);
        mPeek.setVisibility(View.VISIBLE);
        portrait(mPeek.getContext(), mPeekVideo.videoId).into(mPeek);
    }

    private void hidePeek() {
        mPeekDir = 0;
        mPeekVideo = null;
        mPeek.setTranslationY(0f);
        mPeek.setVisibility(View.GONE);
    }

    /** The short {@code step} places away in the queue the Shorts tab built, or null. */
    @Nullable
    private Video neighbour(int step) {
        List<Video> queue = Playlist.instance().getAll();
        Video current = MiniPlayerBridge.getVideo();
        int at = current != null ? queue.indexOf(current) : -1;
        int to = at + step;
        return at >= 0 && to >= 0 && to < queue.size() ? queue.get(to) : null;
    }

    // ---------------------------------------------------------------------------------
    // Play / pause, and the progress line
    // ---------------------------------------------------------------------------------

    /**
     * METUBE(shorts): a tap on the picture, with the double tap answered. Folded here from the
     * deleted {@code ShortsOverlay}, which was the only Shorts surface that could like by double
     * tap: one tap pauses, a second inside the platform's double-tap window likes the short instead
     * (never un-likes, exactly as the watch page's own double tap did) and pops the heart.
     *
     * <p>The pause is therefore deferred by {@link #mDoubleTapTimeoutMs}. That is YouTube's own
     * trade - and the same wait the old full-screen shorts page ran, where a tap that paused and
     * then a second that liked left the short paused. A tap from an accessibility service or a
     * keyboard goes through the host's OnClickListener instead, with no wait, because it can never
     * be the first half of a double tap.</p>
     */
    private void onTapUp() {
        long now = android.os.SystemClock.uptimeMillis();
        boolean second = (now - mLastTapAt) <= mDoubleTapTimeoutMs
                && Math.abs(mDownX - mLastTapX) <= 3 * mTouchSlop
                && Math.abs(mDownY - mLastTapY) <= 3 * mTouchSlop;
        mLastTapAt = now;
        mLastTapX = mDownX;
        mLastTapY = mDownY;
        mHost.removeCallbacks(mSingleTap);
        if (second) {
            mLastTapAt = 0L; // a third tap is a new first tap, not another like
            likeByDoubleTap();
            return;
        }
        mHost.postDelayed(mSingleTap, mDoubleTapTimeoutMs);
    }

    /** METUBE(shorts): double tap = like, through the watch page's own button so both stay in sync. */
    private void likeByDoubleTap() {
        MiniPlayerBridge.ShortsActions actions = MiniPlayerBridge.getShortsActions();
        if (actions == null || !actions.liked) {
            MiniPlayerBridge.shortsLike(); // the splash comes back with the state push
        }
        popHeart();
    }

    /** Double tap: a big heart springs in and floats off (the overlay's animation, same numbers). */
    private void popHeart() {
        if (mHeart == null) {
            return;
        }
        mHeart.animate().cancel();
        mHeart.setAlpha(1f);
        mHeart.setScaleX(0.4f);
        mHeart.setScaleY(0.4f);
        mHeart.setRotation((float) (Math.random() * 24 - 12));
        mHeart.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(2.5f)).setDuration(260)
                .withEndAction(() -> mHeart.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f)
                        .setStartDelay(250).setInterpolator(new DecelerateInterpolator())
                        .setDuration(400).start()).start();
    }

    private void togglePlayPause() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            return;
        }
        boolean play = !player.getPlayWhenReady()
                || player.getPlaybackState() == Player.STATE_ENDED;
        player.setPlayWhenReady(play);
        flash(play);
    }

    /** The tap's answer: a play or pause glyph that pops in the middle and fades. */
    private void flash(boolean playing) {
        mFlash.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        mFlash.animate().cancel();
        mFlash.setAlpha(1f);
        mFlash.setScaleX(0.8f);
        mFlash.setScaleY(0.8f);
        mFlash.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction(() ->
                mFlash.animate().alpha(0f).setStartDelay(450).setDuration(200).start()).start();
    }

    /**
     * The TextureView is a raw surface, not a PlayerView: AspectRatioFrameLayout only supplies its
     * shape, so a MATCH_PARENT child would still stretch the buffer. Give it the exact box for the
     * stream's aspect ratio (rotation included), fitted inside the viewport.
     */
    private void applyTextureBox(float ratio) {
        if (mTexture == null) {
            return;
        }
        int hostW = mHost.getWidth();
        int hostH = mHost.getHeight();
        if (hostW <= 0 || hostH <= 0) {
            return;
        }
        float density = mActivity.getResources().getDisplayMetrics().density;
        // The picture lives between the floating top bar and the floating nav capsule, exactly
        // as the official page does: the text and the rail are overlays on the video, and the
        // video never runs under the bottom bar. Sizing it to the whole host (which extends behind
        // that bar) is what made the picture's bottom collide with the capsule.
        int areaTop = topBarBottomInHostPx();
        int areaBottom = hostH - navTopInsetPx() - Math.round(12 * density);
        int areaH = Math.max(1, areaBottom - areaTop);
        int areaW = hostW;
        // The viewport is also the coordinate frame the cover uses, so both show the same crop.
        ViewGroup.LayoutParams frameRaw = mFrame.getLayoutParams();
        if (frameRaw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) frameRaw;
            if (fp.width != areaW || fp.height != areaH || fp.topMargin != areaTop
                    || fp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)) {
                fp.width = areaW;
                fp.height = areaH;
                fp.topMargin = areaTop;
                fp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                mFrame.setLayoutParams(fp);
            }
        }
        // The cover and the neighbour's portrait are scaled like the picture, not like the whole
        // screen: otherwise the cover shows a different crop than the first frame it gives way to.
        applyMediaViewport(mStill, areaTop, areaH);
        applyMediaViewport(mPeek, areaTop, areaH);
        float areaRatio = areaW / (float) areaH;
        int w;
        int h;
        if (ratio > areaRatio) {
            w = areaW;
            h = Math.min(areaH, Math.round(w / ratio));
            w = Math.round(h * ratio);
        } else {
            h = areaH;
            w = Math.round(h * ratio);
        }
        ViewGroup.LayoutParams raw = mTexture.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER
                || lp.topMargin != 0) {
            lp.width = w;
            lp.height = h;
            lp.topMargin = 0;
            lp.gravity = Gravity.CENTER;
            mTexture.setLayoutParams(lp);
        }
        // Where the visible picture ends, not the screen: the progress line belongs to the video's
        // bottom edge, which sits above the floating nav capsule.
        int boxTop = (areaH - h) / 2;
        mVideoBottomInsetPx = Math.max(0, hostH - areaTop - boxTop - h);
    }

    /** Put a full-bleed cover image on the same rectangle the picture itself occupies. */
    private void applyMediaViewport(View view, int top, int height) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        int width = mHost.getWidth();
        if (lp.width != width || lp.height != height || lp.topMargin != top
                || lp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)) {
            lp.width = width;
            lp.height = height;
            lp.topMargin = top;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            view.setLayoutParams(lp);
        }
    }

    /** The bottom of the floating top bar, in the host's coordinates (0 when it is not laid out). */
    private int topBarBottomInHostPx() {
        View bar = mActivity.findViewById(R.id.mobile_top_glass);
        if (bar == null || bar.getHeight() <= 0) {
            return 0;
        }
        int[] barAt = new int[2];
        int[] hostAt = new int[2];
        bar.getLocationInWindow(barAt);
        mHost.getLocationInWindow(hostAt);
        return Math.max(0, barAt[1] + bar.getHeight() - hostAt[1]);
    }

    /** px from the host's bottom to the floating nav capsule's top. */
    private int navTopInsetPx() {
        if (mPillInsetPx > 0) {
            return mPillInsetPx;
        }
        return Math.round(80 * mActivity.getResources().getDisplayMetrics().density);
    }

    private void applyVideoFit() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            return;
        }
        VideoSize size = player.getVideoSize();
        if (size.width <= 0 || size.height <= 0) {
            return;
        }
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        boolean rotated = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270;
        if (rotated) {
            ratio = 1f / ratio;
        }
        // METUBE(shorts) 8d: the rail used to be scaled to 0.82 here to keep it under the watch
        // page's own row. A scale is not a size: 42dp at 0.82 is a 34dp touch target that also made
        // every glyph land below the 24dp the rest of the player uses. The rail is now sized like
        // every other icon button (@dimen/mobile_player_icon_button, 48dp around a 24dp glyph) and
        // drawn at 1.0, so what the finger hits is what the layout says.
        applyTextureBox(ratio);
        if (size.width != mLastVideoW || size.height != mLastVideoH
                || size.unappliedRotationDegrees != mLastVideoRot) {
            mLastVideoW = size.width;
            mLastVideoH = size.height;
            mLastVideoRot = size.unappliedRotationDegrees;
            Log.d("ShortsHost", "video " + size.width + "x" + size.height
                    + " rot=" + size.unappliedRotationDegrees + " ratio=" + ratio);
        }
    }

    private void onTick() {
        if (!isVisible()) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null || !MiniPlayerBridge.isFullscreen()) {
            hide();
            return;
        }
        mDurationMs = Math.max(0, player.getDuration());
        if (mDurationMs > 0 && !mDragging && !mScrubbing) {
            mProgress.setProgress((int) (player.getCurrentPosition() * 1000 / mDurationMs));
        }
        applyVideoFit(); // the next short may have another shape (portrait fills, landscape fits)
        syncInfoBottomInset();
        startTick();
    }

    private void startTick() {
        Utils.removeCallbacks(mTick);
        Utils.postDelayed(mTick, TICK_MS);
    }

    private void stopTick() {
        Utils.removeCallbacks(mTick);
    }

    private static boolean isEmpty(@Nullable CharSequence text) {
        return text == null || text.length() == 0;
    }

    private static boolean equalsId(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
