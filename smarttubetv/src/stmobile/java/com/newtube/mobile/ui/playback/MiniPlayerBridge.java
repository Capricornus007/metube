package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import java.lang.ref.WeakReference;

/**
 * Hand-off state for the YouTube-style in-app mini-player.
 *
 * <p>The playback Activity OWNS the {@link ExoPlayer} (see {@link MobilePlaybackService} -
 * the service only wires the media session/notification, background audio already survives the
 * Activity being covered). Minimizing therefore does NOT move the player anywhere:
 * {@link MobilePlaybackActivity} stays alive behind {@code MobileBrowseActivity}, and the card
 * simply re-parents the player's session-long {@link SurfaceTexture} into its own TextureView
 * (see {@link #getSessionTexture()}) - the codec never notices the hand-off. This class is only
 * the bridge between those two activities: "is a mini session active?" plus lazy access to the
 * live player/video/texture (lazy so an engine restart inside the playback activity never
 * leaves the card holding released objects).</p>
 *
 * <p>All access is main-thread (activity lifecycle callbacks + view clicks), so plain statics
 * are safe. The activity is held weakly: if the system destroys the backgrounded player
 * activity, {@link #isActive()} turns false on its own and the bar simply hides.</p>
 */
public final class MiniPlayerBridge {
    /**
     * A screen that can dock the live mini card: Home, Search, and Channel all qualify. The
     * player minimizes onto whichever host was RESUMED most recently - that is exactly the
     * screen sitting underneath the translucent playback window, so a video opened from Search
     * minimizes back onto the search results instead of yanking Home to the front.
     */
    public interface MiniHost {
        /** @return true when the mini card was made visible and scheduled for drawing. */
        boolean prepareMiniPlayerForHandoff(Runnable onDrawn);

        /** The ViewManager view class that reorders this host's Activity to the front. */
        Class<?> getMiniHostViewClass();

        /**
         * Distance in px between the host's card bottom edge and the content bottom (Home's
         * 56dp bottom-nav row; 0 for overlay hosts). Lets the minimize morph land exactly on
         * THIS host's card instead of always assuming Home's geometry.
         */
        int getMiniCardBottomOffsetPx();
    }

    /**
     * METUBE(shorts): a screen that plays the session FULL SCREEN in place of its own content: the
     * Shorts tab of the browse page. Same ownership rules as {@link MiniHost} - the playback Activity
     * keeps ExoPlayer AND the session-long {@link SurfaceTexture}, this host only re-parents that
     * texture into a full-screen TextureView and forwards vertical swipes back to the player's
     * existing queue navigation - but the host covers the whole window, so the mini card must give
     * the texture up while it is up (one SurfaceTexture feeds one GL consumer at a time; see
     * MobilePlaybackActivity#detachVideoTexture). The player is invisible behind it and still owns
     * the media session, so the notification, background audio and PiP rules are untouched.
     */
    public interface ShortsHost {
        /** Make the host visible and schedule its draw under the still-translucent player. */
        boolean prepareForShortsHandoff(Runnable onDrawn);

        /** The player pushed the playing short's text (title / channel / views-date / description). */
        void onShortsInfoChanged(@Nullable ShortsInfo info);

        /** The NEW stream reached READY: the host may lift its cover once a frame lands. */
        void onShortsStreamReady();

        /** The session is over (expanded back to the watch page, closed, or the player died). */
        void onShortsSessionEnded();

        /** The watch page's like/dislike/count state changed; the host mirrors its action rail. */
        default void onShortsActionsChanged(@Nullable ShortsActions actions) {
        }

        /** The rail's comments button: open the comments over the host, not over the hidden page. */
        default void onShortsCommentsRequested(@Nullable ShortsActions actions) {
        }


        /** The ViewManager view class that reorders this host's Activity to the front. */
        Class<?> getShortsHostViewClass();
    }

    /**
     * METUBE(shorts): the watch page's action-rail state, mirrored by the in-place host. The
     * values come from the playback Activity's own watch views, so a tap in either surface is
     * reflected in the other without duplicating the like/subscribe logic.
     */
    public static final class ShortsActions {
        public final boolean liked;
        public final boolean disliked;
        public final CharSequence likeCount;
        public final CharSequence commentCount;
        @Nullable
        public final Drawable avatar;
        /** The metadata's comment continuation keys, for the host's own comments panel. */
        @Nullable
        public final String commentsKey;
        @Nullable
        public final String newestCommentsKey;

        public ShortsActions(boolean liked, boolean disliked, CharSequence likeCount,
                CharSequence commentCount, @Nullable Drawable avatar,
                @Nullable String commentsKey, @Nullable String newestCommentsKey) {
            this.liked = liked;
            this.disliked = disliked;
            this.likeCount = likeCount;
            this.commentCount = commentCount;
            this.avatar = avatar;
            this.commentsKey = commentsKey;
            this.newestCommentsKey = newestCommentsKey;
        }
    }

    /**
     * METUBE(shorts): what the in-place player shows as text. The watch page computes all of it
     * (its own counts, dates and the description that arrives with the metadata), so the player
     * pushes its rendered strings rather than the host re-deriving them.
     */
    public static final class ShortsInfo {
        public final String videoId;
        public final CharSequence title;
        public final CharSequence channel;
        public final CharSequence meta;
        public final CharSequence description;

        public ShortsInfo(String videoId, CharSequence title, CharSequence channel,
                CharSequence meta, CharSequence description) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.meta = meta;
            this.description = description;
        }
    }

    /**
     * NEWTUBE(motion): a host card left on screen, frozen on its last frame, while the player comes
     * back over it (see {@link #setPendingCardFold}).
     */
    public interface CardFold {
        /** Hide the card now; run {@code onFolded} once a frame without it is on screen. */
        void fold(@Nullable Runnable onFolded);
    }

    private static final long NAVIGATION_PENDING_MS = 30_000;
    /** METUBE(shorts): a browse tap's request to play the next short IN PLACE must be fresh. */
    private static final long FULLSCREEN_REQUEST_MS = 15_000;
    @Nullable
    private static CardFold sPendingFold;

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static WeakReference<MobilePlaybackActivity> sPendingNavigation = new WeakReference<>(null);
    private static WeakReference<MiniHost> sMiniHost = new WeakReference<>(null);
    private static long sPendingNavigationAtMs;
    private static boolean sActive;
    private static Bitmap sHandoffStill;
    private static Bitmap sMiniEntryStill;
    private static Rect sMiniBounds;

    // METUBE(shorts): the in-place full-screen session (see ShortsHost).
    @Nullable
    private static WeakReference<ShortsHost> sShortsHost = new WeakReference<>(null);
    private static boolean sFullscreen;
    @Nullable
    private static ShortsInfo sShortsInfo;
    private static ShortsActions sShortsActions;
    private static boolean sFullscreenRequested;
    private static long sFullscreenRequestedAtMs;

    private MiniPlayerBridge() {
    }

    /**
     * Track the host screen currently underneath (or about to be underneath) the player. Called
     * from each host's onResume - the last resumed host is the minimize destination. Hosts stay
     * registered while merely paused (the player on top of them must still find them).
     */
    public static void registerMiniHost(MiniHost host) {
        sMiniHost = new WeakReference<>(host);
    }

    public static void unregisterMiniHost(MiniHost host) {
        if (sMiniHost.get() == host) {
            sMiniHost = new WeakReference<>(null);
        }
    }

    /** The registered destination host, or null (deep-linked player with no screen beneath). */
    @Nullable
    static MiniHost getMiniHost() {
        return sMiniHost.get();
    }

    /**
     * Pre-render the destination card while the playback window still covers it. This closes
     * the one-frame gap between hiding the morphed playback Activity and the host's onResume
     * callback. Kept separate from {@link #activate(MobilePlaybackActivity)} because channel
     * navigation has a different mini-player host and must not let this host claim the shared
     * SurfaceTexture.
     */
    static boolean prepareMiniHostForHandoff(Runnable onDrawn) {
        MiniHost host = sMiniHost.get();
        return host != null && host.prepareMiniPlayerForHandoff(onDrawn);
    }

    // ---------------------------------------------------------------------------------
    // METUBE(shorts): the in-place full-screen session (see {@link ShortsHost}).
    // ---------------------------------------------------------------------------------

    /**
     * METUBE(shorts): the browse page is (or is about to be) the screen a short plays on. Called
     * from its onResume, next to {@link #registerMiniHost}: the two are alternatives for the same
     * session, and which one is live is {@link #isFullscreen()}.
     */
    public static void registerShortsHost(ShortsHost host) {
        sShortsHost = new WeakReference<>(host);
        host.onShortsActionsChanged(sShortsActions);
    }

    public static void unregisterShortsHost(ShortsHost host) {
        if (sShortsHost != null && sShortsHost.get() == host) {
            sShortsHost = new WeakReference<>(null);
        }
    }

    @Nullable
    static ShortsHost getShortsHost() {
        return sShortsHost != null ? sShortsHost.get() : null;
    }

    /**
     * METUBE(shorts): a card tap on the browse page asked for the in-place player. One-shot with a
     * time limit: the player consumes it when it binds a short, so a tap that ends up anywhere else
     * (a channel route, an error screen, a stale queue item) simply leaves it to expire and the
     * ordinary watch page opens.
     */
    public static void requestFullscreenHandoff() {
        sFullscreenRequested = true;
        sFullscreenRequestedAtMs = SystemClock.uptimeMillis();
    }

    public static void clearFullscreenRequest() {
        sFullscreenRequested = false;
        sFullscreenRequestedAtMs = 0;
    }

    /**
     * Peek without consuming: the freshly-created player window uses this to suppress its own entry
     * animation for an open that is about to be handed over (the Shorts tab opens a short without a
     * card tap, so no {@code PlayerTransitionBridge} snapshot exists to suppress it the usual way).
     */
    public static boolean hasFullscreenRequest() {
        return sFullscreenRequested
                && SystemClock.uptimeMillis() - sFullscreenRequestedAtMs <= FULLSCREEN_REQUEST_MS;
    }

    /** True once, for the open this request was made for (and only while it is still fresh). */
    static boolean consumeFullscreenRequest() {
        boolean requested = sFullscreenRequested
                && SystemClock.uptimeMillis() - sFullscreenRequestedAtMs <= FULLSCREEN_REQUEST_MS;
        sFullscreenRequested = false;
        sFullscreenRequestedAtMs = 0;
        return requested;
    }

    /** The player is now rendered by a full-screen host rather than by its own window. */
    static void activateFullscreen(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        sActive = true;
        sFullscreen = true;
    }

    /** True while the session is played in place (the mini card must stay out of the way). */
    public static boolean isFullscreen() {
        return sFullscreen && isActive();
    }

    /** The strings the watch page renders for this short, for the host's info block. */
    public static void setShortsInfo(@Nullable ShortsInfo info) {
        sShortsInfo = info;
        ShortsHost host = getShortsHost();
        if (host != null) {
            host.onShortsInfoChanged(info);
        }
    }

    @Nullable
    public static ShortsInfo getShortsInfo() {
        return sShortsInfo;
    }

    /** The watch page pushed a new action-rail snapshot; mirror it in the in-place host. */
    public static void setShortsActions(@Nullable ShortsActions actions) {
        sShortsActions = actions;
        ShortsHost host = getShortsHost();
        if (host != null) {
            host.onShortsActionsChanged(actions);
        }
    }

    @Nullable
    public static ShortsActions getShortsActions() {
        return sShortsActions;
    }

    /** The host's rail forwards to the watch page's own buttons, so both surfaces share state. */
    public static void shortsLike() {
        if (isActive()) {
            sActivity.get().hostShortsLike();
        }
    }

    public static void shortsDislike() {
        if (isActive()) {
            sActivity.get().hostShortsDislike();
        }
    }

    public static void shortsComments() {
        ShortsHost host = getShortsHost();
        if (host != null) {
            host.onShortsCommentsRequested(getShortsActions());
        }
    }

    public static void shortsShare() {
        if (isActive()) {
            sActivity.get().hostShortsShare();
        }
    }

    public static void shortsMore() {
        ShortsHost host = getShortsHost();
        if (isActive() && host instanceof android.app.Activity) {
            // The full player menu (quality, audio, captions, speed, PiP, ambient, more) is built
            // from this Activity's controllers, but its sheets must use the visible host's window.
            sActivity.get().hostShortsMore((android.app.Activity) host);
        }
    }

    /** A comment's link to another video: keep playing it in the session behind the host. */
    public static void openShortsVideo(String videoId) {
        if (isActive() && videoId != null) {
            sActivity.get().hostOpenVideo(videoId);
        }
    }

    /** The new stream is READY: the host can stop covering the texture with a thumbnail. */
    public static void notifyShortsReady() {
        ShortsHost host = getShortsHost();
        if (host != null && isFullscreen()) {
            host.onShortsStreamReady();
        }
    }

    /** Prepare the host's full-screen surface before the player's window is reordered away. */
    static boolean prepareShortsHostForHandoff(Runnable onDrawn) {
        ShortsHost host = getShortsHost();
        return host != null && host.prepareForShortsHandoff(onDrawn);
    }

    /** Swipe up: the player behind us moves to the next short of the queue. */
    public static void playNextShort() {
        if (isActive()) {
            sActivity.get().hostPlayNextShort();
        }
    }

    /** Swipe down: the previous short. */
    public static void playPreviousShort() {
        if (isActive()) {
            sActivity.get().hostPlayPreviousShort();
        }
    }

    /** The host's scrub line: the same seek the watch page's own bar performs. */
    public static void seekShortTo(long positionMs) {
        if (isActive()) {
            sActivity.get().hostSeekShortTo(positionMs);
        }
    }

    /**
     * The host's real size: the player caps NEW chunks at the window the video is drawn in
     * (VideoViewportCap), so an in-place full-screen host must be reported as full screen - the
     * mini card's cap would drop a short to 360p.
     */
    public static void setFullscreenViewport(int widthPx, int heightPx) {
        if (isActive()) {
            sActivity.get().hostSetViewport(widthPx, heightPx);
        }
    }

    /** The host no longer renders the session (it left, or the session ended): stand down. */
    private static void notifyShortsSessionEnded() {
        ShortsHost host = getShortsHost();
        if (host != null) {
            host.onShortsSessionEnded();
        }
    }

    /**
     * NEWTUBE(motion): a host pausing with a mini session docked detaches its texture (the player
     * may be about to take it back) but keeps the card showing that last frame instead of hiding
     * it. The expanding player's window draws ~50 ms after the host pauses (Pixel 9), and a card
     * hidden at pause read as a blink: 4 frames of no video before the morph. The player folds the
     * card once its own frame over the card is on screen ({@link #foldHostCard}); a host that
     * resumes or stops first clears or folds it itself. Deliberately not part of the session state
     * that {@link #deactivate()} clears: the player deactivates before it folds.
     */
    public static void setPendingCardFold(@Nullable CardFold fold) {
        sPendingFold = fold;
    }

    public static void clearPendingCardFold(CardFold fold) {
        if (sPendingFold == fold) {
            sPendingFold = null;
        }
    }

    /**
     * NEWTUBE(motion): a host's X is closing the session once its card has animated away; the
     * runnable aborts that (and puts the sound back). A new video delivered to this same live player
     * meanwhile (PlaybackPresenter re-uses it) must not be closed with it: the player calls
     * {@link #cancelClosing()} when it is handed a different video.
     */
    @Nullable
    private static Runnable sClosingAbort;

    public static void setClosing(@Nullable Runnable abort) {
        sClosingAbort = abort;
    }

    static void cancelClosing() {
        Runnable abort = sClosingAbort;
        sClosingAbort = null;
        if (abort != null) {
            abort.run();
        }
    }

    /** The player now covers the card: fold it, then run {@code onFolded} (at once if none waits). */
    static void foldHostCard(@Nullable Runnable onFolded) {
        CardFold fold = sPendingFold;
        sPendingFold = null;
        if (fold != null) {
            fold.fold(onFolded);
        } else if (onFolded != null) {
            onFolded.run();
        }
    }

    /** Called by the playback activity right before it backgrounds itself into mini mode. */
    static void activate(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        sActive = true;
    }

    /**
     * Mark a channel route that should become an in-app mini session only if its destination
     * Activity is actually created. Channel-id resolution can be asynchronous, so activating here
     * would strand a detached/shrunk player when lookup fails.
     */
    static void prepareNavigation(MobilePlaybackActivity activity) {
        sPendingNavigation = new WeakReference<>(activity);
        sPendingNavigationAtMs = SystemClock.uptimeMillis();
    }

    /** Called by the channel Activity once the pending route has succeeded. */
    public static boolean completePendingNavigation() {
        MobilePlaybackActivity activity = sPendingNavigation.get();
        boolean fresh = activity != null
                && SystemClock.uptimeMillis() - sPendingNavigationAtMs <= NAVIGATION_PENDING_MS;
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;

        return fresh && !activity.isFinishing() && !activity.isDestroyed()
                && activity.minimizeForNavigation();
    }

    /** Called when the playback activity takes its surface back (expand / new video / destroy). */
    public static void deactivate() {
        // METUBE(shorts): the in-place host loses its picture with the session, whatever ended it -
        // the player taking the texture back, the bar's X, or the activity dying behind our back.
        boolean wasFullscreen = sFullscreen;
        sActive = false;
        sFullscreen = false;
        sShortsInfo = null;
        sActivity = new WeakReference<>(null);
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;
        sHandoffStill = null;
        sMiniEntryStill = null;
        sMiniBounds = null;
        if (wasFullscreen) {
            notifyShortsSessionEnded();
        }
    }

    /**
     * The session-long video {@link SurfaceTexture} (see MobilePlaybackActivity's persistent
     * surface docs). The Browse card re-parents this into its own TextureView while the mini
     * session is active - the codec's output surface never changes, so playback never stalls.
     */
    @Nullable
    public static SurfaceTexture getSessionTexture() {
        return isActive() ? sActivity.get().getSessionTexture() : null;
    }

    /** Last card frame, captured by Browse when it detaches; shown by the expanding player. */
    public static void setHandoffStill(@Nullable Bitmap frame) {
        sHandoffStill = frame;
    }

    /** Initial full-size frame shown while a newly-created mini TextureView adopts the session. */
    static void setMiniEntryStill(@Nullable Bitmap frame) {
        sMiniEntryStill = frame;
    }

    /** Consume the initial mini frame (single use by the destination screen). */
    @Nullable
    public static Bitmap takeMiniEntryStill() {
        Bitmap frame = sMiniEntryStill;
        sMiniEntryStill = null;
        return frame;
    }

    /** Exact on-screen mini rectangle, used to reverse the morph without a geometry jump. */
    public static void setMiniBounds(@Nullable Rect bounds) {
        sMiniBounds = bounds != null ? new Rect(bounds) : null;
    }

    @Nullable
    static Rect takeMiniBounds() {
        Rect bounds = sMiniBounds;
        sMiniBounds = null;
        return bounds;
    }

    /** Consume the captured card frame (single use). */
    @Nullable
    static Bitmap takeHandoffStill() {
        Bitmap frame = sHandoffStill;
        sHandoffStill = null;
        return frame;
    }

    /** True while a live, still-alive player session is docked in the mini bar. */
    public static boolean isActive() {
        if (!sActive) {
            return false;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getSharedPlayer() == null) {
            deactivate(); // the session died behind our back - self-heal
            return false;
        }
        return true;
    }

    /** The live player to render in the bar, or null when no mini session is active. */
    @Nullable
    public static ExoPlayer getPlayer() {
        return isActive() ? sActivity.get().getSharedPlayer() : null;
    }

    /**
     * NEWTUBE(issue #9): give a host card's video frame (an {@link AspectRatioFrameLayout}, see
     * {@code mobile_mini_player_frame}) the playing video's shape. The card is a fixed 16:9 box and
     * a TextureView scales its buffer to its own bounds, so a 2.35:1 film, a 4:3 clip or a vertical
     * video filled the card stretched. Now it is letterboxed on the card's black background, like
     * the watch page's video box the minimize morph shrinks into it. Unknown size: unchanged.
     * Cheap and idempotent (setAspectRatio only relayouts on a change), so hosts call it from their
     * sync and their progress tick - the tick picks up an autoplayed video of another shape.
     */
    public static void fitToVideo(@Nullable View frame) {
        ExoPlayer player = getPlayer();
        if (!(frame instanceof AspectRatioFrameLayout) || player == null) {
            return;
        }
        VideoSize size = player.getVideoSize();
        if (size.width > 0 && size.height > 0) {
            int width = size.width;
            int height = size.height;
            // A 90/270 rotation makes the displayed frame portrait even when the codec reports a
            // landscape buffer; using the raw width/height would stretch the video sideways.
            if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
                int swap = width;
                width = height;
                height = swap;
            }
            ((AspectRatioFrameLayout) frame).setAspectRatio(
                    width * size.pixelWidthHeightRatio / height);
        }
    }

    /**
     * NEWTUBE(loudness): the session's volume as its player controller keeps it, apart from loudness
     * normalization (a direct ExoPlayer write would be undone by the next normalization update);
     * -1 when no mini session is active.
     */
    public static float getVolume() {
        return isActive() ? sActivity.get().getVolume() : -1;
    }

    /** NEWTUBE(loudness): see {@link #getVolume()}. No-op when no mini session is active. */
    public static void setVolume(float volume) {
        if (isActive()) {
            sActivity.get().setVolume(volume);
        }
    }

    /** Metadata of the playing video (title/author for the bar), or null. */
    @Nullable
    public static Video getVideo() {
        return isActive() ? sActivity.get().getVideo() : null;
    }

    /**
     * Expand: bring the (still alive) playback activity back to the front. Its onResume
     * re-claims the video surface and deactivates this bridge. The caller must detach the
     * mini bar's PlayerView first so the surface is free.
     */
    public static void expand(Context context) {
        ViewManager.instance(context).startView(PlaybackView.class);
    }

    /** Close from the bar's X: stop playback and finish the hidden playback activity. */
    public static void close() {
        MobilePlaybackActivity activity = sActivity.get();
        deactivate();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.closeFromMiniPlayer();
        }
    }
}
