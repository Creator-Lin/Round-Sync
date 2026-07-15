package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.thumbnails.ThumbnailRepository;

public class FileExplorerRecyclerViewAdapter extends RecyclerView.Adapter<FileExplorerRecyclerViewAdapter.ViewHolder> {

    public static final int VIEW_MODE_LIST = 0;
    public static final int VIEW_MODE_GRID = 1;

    public static final int THUMBNAIL_SIZE_SMALL = 0;
    public static final int THUMBNAIL_SIZE_MEDIUM = 1;
    public static final int THUMBNAIL_SIZE_LARGE = 2;
    public static final int THUMBNAIL_SIZE_EXTRA_LARGE = 3;

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;

    private List<FileItem> files;
    private final View emptyView;
    private final View noSearchResultsView;
    private final OnClickListener listener;
    private boolean isInSelectMode;
    private List<FileItem> selectedItems;
    private boolean isInMoveMode;
    private boolean isInSearchMode;
    private boolean canSelect;
    private boolean optionsDisabled;
    private boolean wrapFileNames;
    private final Context context;
    private final ThumbnailRepository thumbnailRepository;
    private final SharedPreferences sharedPreferences;
    private final String imageThumbnailsEnabledPreferenceKey;
    private final String videoThumbnailsEnabledPreferenceKey;
    private final SharedPreferences.OnSharedPreferenceChangeListener thumbnailPreferenceListener;
    private final ColorStateList fileIconTint;
    private final Map<String, Float> thumbnailAspectRatios;
    private final Map<String, Integer> gridFileNameHeights;
    private RecyclerView attachedRecyclerView;
    private boolean gridRelayoutPosted;
    private int lastGridSpanCount;
    private int lastGridContentWidth;
    private int viewMode;
    private int thumbnailSize;

    public interface OnClickListener {
        void onFileClicked(FileItem fileItem);
        void onDirectoryClicked(FileItem fileItem, int position);
        void onFilesSelected();
        void onFileDeselected();
        void onFileOptionsClicked(View view, FileItem fileItem);
    }

    public interface ThumbnailRegenerationCallback {
        void onThumbnailRegenerated(boolean success);
    }

    public FileExplorerRecyclerViewAdapter(Context context, View emptyView,
                                           View noSearchResultsView, OnClickListener listener) {
        files = new ArrayList<>();
        this.context = context;
        this.emptyView = emptyView;
        this.noSearchResultsView = noSearchResultsView;
        this.listener = listener;
        isInSelectMode = false;
        selectedItems = new ArrayList<>();
        isInMoveMode = false;
        isInSearchMode = false;
        canSelect = true;
        wrapFileNames = true;
        optionsDisabled = false;
        viewMode = VIEW_MODE_LIST;
        thumbnailSize = THUMBNAIL_SIZE_MEDIUM;
        thumbnailRepository = ThumbnailRepository.getInstance(context);
        thumbnailAspectRatios = new HashMap<>();
        gridFileNameHeights = new HashMap<>();
        gridRelayoutPosted = false;
        lastGridSpanCount = -1;
        lastGridContentWidth = -1;
        TypedArray tintAttributes = context.obtainStyledAttributes(
                new int[]{com.google.android.material.R.attr.colorOnSurface});
        try {
            fileIconTint = tintAttributes.getColorStateList(0);
        } finally {
            tintAttributes.recycle();
        }
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        imageThumbnailsEnabledPreferenceKey = context.getString(
                R.string.pref_key_thumbnail_image_enabled);
        videoThumbnailsEnabledPreferenceKey = context.getString(
                R.string.pref_key_thumbnail_video_enabled);
        thumbnailPreferenceListener = (preferences, key) -> {
            boolean imagePreferenceChanged = imageThumbnailsEnabledPreferenceKey.equals(key);
            boolean videoPreferenceChanged = videoThumbnailsEnabledPreferenceKey.equals(key);
            if (!imagePreferenceChanged && !videoPreferenceChanged) {
                return;
            }
            RecyclerView recyclerView = attachedRecyclerView;
            if (recyclerView != null) {
                recyclerView.post(() -> notifyThumbnailTypeChanged(
                        imagePreferenceChanged, videoPreferenceChanged));
            }
        };
    }

    public void notifyThumbnailCacheUpdated(ThumbnailRepository.CacheType cacheType) {
        notifyThumbnailTypeChanged(
                cacheType == ThumbnailRepository.CacheType.IMAGE,
                cacheType == ThumbnailRepository.CacheType.VIDEO);
    }

    private void notifyThumbnailTypeChanged(boolean imageChanged, boolean videoChanged) {
        for (int position = 0; position < files.size(); position++) {
            FileItem item = files.get(position);
            if ((imageChanged && thumbnailRepository.isImageThumbnailCandidate(item))
                    || (videoChanged && thumbnailRepository.isVideoThumbnailCandidate(item))) {
                notifyItemChanged(position);
            }
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
        sharedPreferences.registerOnSharedPreferenceChangeListener(thumbnailPreferenceListener);
        scheduleGridRelayout();
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(thumbnailPreferenceListener);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
            gridRelayoutPosted = false;
            lastGridSpanCount = -1;
            lastGridContentWidth = -1;
        }
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override
    public int getItemViewType(int position) {
        return viewMode == VIEW_MODE_GRID ? TYPE_GRID : TYPE_LIST;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_GRID
                ? R.layout.fragment_file_explorer_grid_item
                : R.layout.fragment_file_explorer_item;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ViewHolder(view, viewType == TYPE_GRID);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final FileItem item = files.get(position);
        holder.cancelThumbnailRequest();
        holder.fileItem = item;
        holder.fileIcon.setTag(null);
        showThemedIcon(holder.fileIcon, R.drawable.ic_file);
        showThemedIcon(holder.dirIcon, R.drawable.ic_folder);
        configureImageArea(holder);

        if (item.isDir()) {
            holder.dirIcon.setVisibility(View.VISIBLE);
            holder.fileIcon.setVisibility(View.GONE);
            holder.fileSize.setVisibility(View.GONE);
            holder.interpunct.setVisibility(View.GONE);
        } else {
            holder.fileIcon.setVisibility(View.VISIBLE);
            holder.dirIcon.setVisibility(View.GONE);
            holder.fileSize.setText(item.getHumanReadableSize());
            holder.fileSize.setVisibility(holder.grid ? View.GONE : View.VISIBLE);
            holder.interpunct.setVisibility(holder.grid ? View.GONE : View.VISIBLE);
            bindFilePreview(holder, item);
        }

        RemoteItem itemRemote = item.getRemote();
        if (holder.grid || (!itemRemote.isDirectoryModifiedTimeSupported() && item.isDir())) {
            holder.fileModTime.setVisibility(View.GONE);
        } else {
            holder.fileModTime.setVisibility(View.VISIBLE);
            holder.fileModTime.setText(item.getHumanReadableModTime());
        }

        holder.fileName.setText(item.getName());
        configureFileName(holder, position);

        if (isInSelectMode && selectedItems.contains(item)) {
            holder.view.setBackgroundColor(getSelectionBackgroundColor());
        } else {
            holder.view.setBackgroundColor(Color.TRANSPARENT);
        }

        if (isInMoveMode) {
            holder.view.setAlpha(item.isDir() ? 1f : .5f);
        } else if (holder.view.getAlpha() == .5f) {
            holder.view.setAlpha(1f);
        }

        if ((isInSelectMode || isInMoveMode) && !optionsDisabled) {
            holder.fileOptions.setVisibility(View.INVISIBLE);
        } else if (optionsDisabled) {
            holder.fileOptions.setVisibility(View.GONE);
        } else {
            holder.fileOptions.setVisibility(View.VISIBLE);
            holder.fileOptions.setOnClickListener(v -> listener.onFileOptionsClicked(v, item));
        }

        View.OnClickListener primaryClickListener = view -> {
            if (isInSelectMode) {
                onLongClickAction(item, holder);
            } else {
                onClickAction(item, holder.getBindingAdapterPosition());
            }
        };
        View.OnLongClickListener primaryLongClickListener = view -> {
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder);
            }
            return true;
        };

        // Explicitly attach the same action to every visible part of the primary
        // item area. This avoids child views consuming a tap before it reaches the
        // row/card and makes list and grid interaction consistent.
        holder.view.setOnClickListener(primaryClickListener);
        holder.icons.setOnClickListener(primaryClickListener);
        holder.fileIcon.setOnClickListener(primaryClickListener);
        holder.dirIcon.setOnClickListener(primaryClickListener);
        holder.fileName.setOnClickListener(primaryClickListener);
        if (holder.fileNameContainer != null) {
            holder.fileNameContainer.setOnClickListener(primaryClickListener);
        }
        holder.view.setOnLongClickListener(primaryLongClickListener);
        holder.icons.setOnLongClickListener(primaryLongClickListener);
        holder.fileIcon.setOnLongClickListener(primaryLongClickListener);
        holder.dirIcon.setOnLongClickListener(primaryLongClickListener);
        holder.fileName.setOnLongClickListener(primaryLongClickListener);
        if (holder.fileNameContainer != null) {
            holder.fileNameContainer.setOnLongClickListener(primaryLongClickListener);
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.cancelThumbnailRequest();
        holder.fileIcon.setTag(null);
        showThemedIcon(holder.fileIcon, R.drawable.ic_file);
        super.onViewRecycled(holder);
    }

    private void bindFilePreview(@NonNull ViewHolder holder, FileItem item) {
        if (!thumbnailRepository.supportsThumbnail(item)) {
            holder.fileIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            showThemedIcon(holder.fileIcon, R.drawable.ic_file);
            return;
        }

        String requestKey = thumbnailRepository.getRequestKey(item);
        holder.fileIcon.setTag(requestKey);
        // Keep the placeholder icon intact while the remote thumbnail is loading.
        // Once available, the bitmap's own aspect ratio determines the grid preview
        // height so portrait and landscape media are both shown without cropping.
        holder.fileIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        showThemedIcon(holder.fileIcon, R.drawable.ic_file);

        if (holder.grid) {
            Float cachedAspectRatio = thumbnailAspectRatios.get(requestKey);
            if (cachedAspectRatio != null && cachedAspectRatio > 0f) {
                applyGridPreviewAspectRatio(holder, cachedAspectRatio);
            }
        }

        WeakReference<ImageView> targetReference = new WeakReference<>(holder.fileIcon);
        WeakReference<ViewHolder> holderReference = new WeakReference<>(holder);
        holder.thumbnailRequest = thumbnailRepository.load(item, getThumbnailTargetPx(), bitmap -> {
            ImageView target = targetReference.get();
            ViewHolder boundHolder = holderReference.get();
            if (target == null || boundHolder == null
                    || boundHolder.fileIcon != target
                    || !requestKey.equals(target.getTag())) {
                return;
            }
            if (bitmap == null) {
                target.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                showThemedIcon(target, R.drawable.ic_file);
            } else {
                target.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ImageViewCompat.setImageTintList(target, null);
                target.setImageBitmap(bitmap);
                if (boundHolder.grid && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                    float aspectRatio = bitmap.getHeight() / (float) bitmap.getWidth();
                    thumbnailAspectRatios.put(requestKey, aspectRatio);
                    if (applyGridPreviewAspectRatio(boundHolder, aspectRatio)) {
                        scheduleGridRelayout();
                    }
                }
            }
        });
    }

    private void showThemedIcon(ImageView imageView, int drawableResource) {
        ImageViewCompat.setImageTintList(imageView, fileIconTint);
        imageView.setImageResource(drawableResource);
    }

    private void configureImageArea(ViewHolder holder) {
        if (!holder.grid) {
            holder.fileIcon.setAdjustViewBounds(false);
            holder.dirIcon.setAdjustViewBounds(false);
            holder.fileIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.dirIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return;
        }

        int previewWidth = getGridContentWidthPx();
        // Use a square placeholder while the thumbnail is loading. As soon as the
        // bitmap arrives, applyGridPreviewAspectRatio replaces this with the media's
        // real rotated aspect ratio. Resetting here is essential for recycled views.
        setGridPreviewHeight(holder, previewWidth, previewWidth);

        if (holder.icons instanceof ViewGroup) {
            ((ViewGroup) holder.icons).setClipChildren(true);
            ((ViewGroup) holder.icons).setClipToPadding(true);
        }

        ViewGroup.LayoutParams fileParams = holder.fileIcon.getLayoutParams();
        fileParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        fileParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        holder.fileIcon.setLayoutParams(fileParams);
        holder.fileIcon.setAdjustViewBounds(false);
        holder.fileIcon.setMinimumHeight(0);
        holder.fileIcon.setMaxHeight(Integer.MAX_VALUE);
        holder.fileIcon.setPadding(0, 0, 0, 0);
        holder.fileIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        ViewGroup.LayoutParams directoryParams = holder.dirIcon.getLayoutParams();
        directoryParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        directoryParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        holder.dirIcon.setLayoutParams(directoryParams);
        holder.dirIcon.setAdjustViewBounds(false);
        holder.dirIcon.setMinimumHeight(0);
        holder.dirIcon.setMaxHeight(Integer.MAX_VALUE);
        holder.dirIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    private boolean applyGridPreviewAspectRatio(ViewHolder holder, float heightToWidthRatio) {
        if (!holder.grid || heightToWidthRatio <= 0f
                || Float.isInfinite(heightToWidthRatio)
                || Float.isNaN(heightToWidthRatio)) {
            return false;
        }
        int previewWidth = holder.icons.getWidth();
        if (previewWidth <= 0) {
            previewWidth = getGridContentWidthPx();
        }
        int previewHeight = Math.max(1, Math.round(previewWidth * heightToWidthRatio));
        return setGridPreviewHeight(holder, previewWidth, previewHeight);
    }

    private boolean setGridPreviewHeight(ViewHolder holder, int previewWidth, int previewHeight) {
        ViewGroup.LayoutParams iconAreaParams = holder.icons.getLayoutParams();
        boolean heightChanged = iconAreaParams.height != previewHeight;
        if (heightChanged) {
            iconAreaParams.height = previewHeight;
            holder.icons.setLayoutParams(iconAreaParams);
        }
        holder.icons.setMinimumHeight(0);
        configureGridOptionsButton(holder, previewWidth, previewHeight);
        if (heightChanged) {
            // A thumbnail is loaded asynchronously, after GridLayoutManager has
            // already measured the complete row using the square placeholder.
            // Requesting only the image container is not sufficient when the new
            // height is smaller: row siblings and following rows can retain the old
            // chunk height and leave a permanent blank band. Mark the whole card as
            // dirty; scheduleGridRelayout() will coalesce all thumbnail callbacks
            // into one full visible-grid measurement on the next animation frame.
            holder.view.requestLayout();
        }
        return heightChanged;
    }

    /**
     * Coalesces asynchronous thumbnail geometry changes into one RecyclerView pass.
     *
     * GridLayoutManager measures all children in a row as one chunk. A single child
     * changing from the square loading placeholder to its real media ratio therefore
     * requires the row siblings and the rows below it to be measured again. Android
     * does not always promote a nested child requestLayout() to a complete grid pass,
     * which previously left stale row heights after re-entering a remote.
     */
    private void scheduleGridRelayout() {
        RecyclerView recyclerView = attachedRecyclerView;
        if (viewMode != VIEW_MODE_GRID || recyclerView == null || gridRelayoutPosted) {
            return;
        }
        gridRelayoutPosted = true;
        recyclerView.postOnAnimation(() -> {
            if (attachedRecyclerView != recyclerView) {
                gridRelayoutPosted = false;
                return;
            }
            if (recyclerView.isComputingLayout()) {
                // Do not mutate row-local filename containers during a RecyclerView
                // layout callback. Deferring one more frame keeps the pass safe.
                gridRelayoutPosted = false;
                scheduleGridRelayout();
                return;
            }
            gridRelayoutPosted = false;
            RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
            if (!(layoutManager instanceof GridLayoutManager)) {
                return;
            }

            int spanCount = Math.max(1, ((GridLayoutManager) layoutManager).getSpanCount());
            int contentWidth = getGridContentWidthPx();
            if (spanCount != lastGridSpanCount || contentWidth != lastGridContentWidth) {
                // Row-local filename heights depend on both the column count and the
                // actual text width. Re-entering a remote can bind the first holders
                // before AutoFitGridLayoutManager has settled on its final span count.
                gridFileNameHeights.clear();
                lastGridSpanCount = spanCount;
                lastGridContentWidth = contentWidth;
            }

            // Recalculate only attached cards. Off-screen cards will receive the same
            // calculation normally when they bind, while visible rows are corrected
            // immediately without restarting thumbnail requests.
            for (int childIndex = 0; childIndex < recyclerView.getChildCount(); childIndex++) {
                View child = recyclerView.getChildAt(childIndex);
                RecyclerView.ViewHolder rawHolder = recyclerView.getChildViewHolder(child);
                if (!(rawHolder instanceof ViewHolder)) {
                    continue;
                }
                ViewHolder holder = (ViewHolder) rawHolder;
                int position = holder.getBindingAdapterPosition();
                if (!holder.grid || position == RecyclerView.NO_POSITION) {
                    continue;
                }
                configureFileName(holder, position);
                holder.view.requestLayout();
            }

            layoutManager.requestLayout();
            recyclerView.requestLayout();
        });
    }

    private void configureGridOptionsButton(ViewHolder holder, int previewWidth, int previewHeight) {
        // Keep the transparent touch surface compact so the centered glyph sits close
        // to the preview's real top-right corner. Each grid size has a proportionally
        // sized target; unlike the previous 22-32dp area, it no longer pushes the
        // visible dots inward even when the outer margin is already small.
        int preferredSize;
        switch (thumbnailSize) {
            case THUMBNAIL_SIZE_SMALL:
                preferredSize = dpToPx(16);
                break;
            case THUMBNAIL_SIZE_LARGE:
                preferredSize = dpToPx(20);
                break;
            case THUMBNAIL_SIZE_EXTRA_LARGE:
                preferredSize = dpToPx(22);
                break;
            case THUMBNAIL_SIZE_MEDIUM:
            default:
                preferredSize = dpToPx(18);
                break;
        }

        // A very wide video can produce a shallow preview. Clamp the complete touch
        // surface to that preview while keeping it flush with the top and end edges.
        int maximumSizeInsidePreview = Math.max(1, Math.min(previewWidth, previewHeight));
        int optionButtonSize = Math.min(preferredSize, maximumSizeInsidePreview);

        FrameLayout.LayoutParams optionParams =
                (FrameLayout.LayoutParams) holder.fileOptions.getLayoutParams();
        optionParams.width = optionButtonSize;
        optionParams.height = optionButtonSize;
        optionParams.gravity = Gravity.TOP | Gravity.END;
        optionParams.topMargin = 0;
        optionParams.setMarginEnd(0);
        holder.fileOptions.setLayoutParams(optionParams);
        holder.fileOptions.setMinimumWidth(0);
        holder.fileOptions.setMinimumHeight(0);

        // The drawable remains smaller than the touch surface, but both are reduced.
        // Thirty percent padding leaves a grey glyph around 6-9dp across the four
        // grid sizes while keeping the button itself at only 16-22dp.
        int optionPadding = Math.min(
                Math.max(0, (optionButtonSize - 1) / 2),
                Math.max(0, Math.round(optionButtonSize * .30f)));
        holder.fileOptions.setPadding(
                optionPadding,
                optionPadding,
                optionPadding,
                optionPadding);
    }

    private void configureFileName(ViewHolder holder, int position) {
        if (holder.grid) {
            holder.fileName.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            holder.fileName.setEllipsize(TextUtils.TruncateAt.END);
            holder.fileName.setSingleLine(false);
            holder.fileName.setMinLines(1);
            holder.fileName.setMaxLines(3);

            // The TextView itself remains wrap_content (one, two, or three lines).
            // Only its row-local container reserves the tallest filename height in
            // that row. Since every item then has the same area below its preview,
            // GridLayoutManager's equal row measurement plus bottom gravity aligns
            // every thumbnail bottom edge without globally wasting three lines.
            if (holder.fileNameContainer != null) {
                ViewGroup.LayoutParams params = holder.fileNameContainer.getLayoutParams();
                int rowNameHeight = getGridRowFileNameHeight(holder, position);
                if (params.height != rowNameHeight) {
                    params.height = rowNameHeight;
                    holder.fileNameContainer.setLayoutParams(params);
                }
                holder.fileNameContainer.setMinimumHeight(0);
            }
            return;
        }

        holder.fileName.setGravity(Gravity.START);
        holder.fileName.setMaxLines(Integer.MAX_VALUE);
        if (wrapFileNames) {
            holder.fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            holder.fileName.setSingleLine(true);
        } else {
            holder.fileName.setEllipsize(null);
            holder.fileName.setSingleLine(false);
        }
    }

    private int getGridRowFileNameHeight(ViewHolder holder, int position) {
        int spanCount = getGridSpanCount();
        int safePosition = Math.max(0, Math.min(position, Math.max(0, files.size() - 1)));
        int rowStart = (safePosition / spanCount) * spanCount;
        int rowEnd = Math.min(files.size(), rowStart + spanCount);
        int textWidth = Math.max(1, getGridContentWidthPx()
                - holder.fileName.getCompoundPaddingLeft()
                - holder.fileName.getCompoundPaddingRight());

        int maximumHeight = measureGridFileNameHeight(holder.fileName, " ", textWidth);
        for (int index = rowStart; index < rowEnd; index++) {
            maximumHeight = Math.max(maximumHeight,
                    measureGridFileNameHeight(holder.fileName, files.get(index).getName(), textWidth));
        }
        return Math.max(1, maximumHeight);
    }

    private int measureGridFileNameHeight(TextView textView, CharSequence text, int width) {
        CharSequence safeText = TextUtils.isEmpty(text) ? " " : text;
        String cacheKey = width + "\u0000" + safeText;
        Integer cachedHeight = gridFileNameHeights.get(cacheKey);
        if (cachedHeight != null) {
            return cachedHeight;
        }

        StaticLayout layout = StaticLayout.Builder.obtain(
                        safeText, 0, safeText.length(), textView.getPaint(), width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(textView.getIncludeFontPadding())
                .setLineSpacing(textView.getLineSpacingExtra(), textView.getLineSpacingMultiplier())
                .setBreakStrategy(textView.getBreakStrategy())
                .setHyphenationFrequency(textView.getHyphenationFrequency())
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(width)
                .setMaxLines(3)
                .build();
        int measuredHeight = layout.getHeight();
        gridFileNameHeights.put(cacheKey, measuredHeight);
        return measuredHeight;
    }

    private int getGridSpanCount() {
        RecyclerView recyclerView = attachedRecyclerView;
        if (recyclerView != null
                && recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            return Math.max(1,
                    ((GridLayoutManager) recyclerView.getLayoutManager()).getSpanCount());
        }

        int minimumColumnWidth = dpToPx(getGridColumnWidthDp());
        if (recyclerView != null) {
            int availableWidth = recyclerView.getWidth()
                    - recyclerView.getPaddingLeft()
                    - recyclerView.getPaddingRight();
            if (availableWidth > 0) {
                return Math.max(1, availableWidth / minimumColumnWidth);
            }
        }
        return 1;
    }

    private int getThumbnailTargetPx() {
        if (viewMode == VIEW_MODE_LIST) {
            return Math.min(1280, dpToPx(112));
        }
        return Math.min(1280, getGridContentWidthPx());
    }

    private int getGridContentWidthPx() {
        int minimumColumnWidth = dpToPx(getGridColumnWidthDp());
        int actualColumnWidth = minimumColumnWidth;
        RecyclerView recyclerView = attachedRecyclerView;
        if (recyclerView != null) {
            int availableWidth = recyclerView.getWidth()
                    - recyclerView.getPaddingLeft()
                    - recyclerView.getPaddingRight();
            if (availableWidth > 0) {
                int spanCount = Math.max(1, availableWidth / minimumColumnWidth);
                actualColumnWidth = availableWidth / spanCount;
            }
        }

        int itemMargin = context.getResources().getDimensionPixelSize(
                R.dimen.file_grid_item_margin);
        int itemPadding = context.getResources().getDimensionPixelSize(
                R.dimen.file_grid_item_padding);
        int horizontalInsets = 2 * (itemMargin + itemPadding);
        return Math.max(dpToPx(64), actualColumnWidth - horizontalInsets);
    }

    public int getGridColumnWidthDp() {
        switch (thumbnailSize) {
            case THUMBNAIL_SIZE_SMALL:
                return 112;
            case THUMBNAIL_SIZE_LARGE:
                return 220;
            case THUMBNAIL_SIZE_EXTRA_LARGE:
                return 300;
            case THUMBNAIL_SIZE_MEDIUM:
            default:
                return 156;
        }
    }

    private int dpToPx(int dp) {
        return Math.max(1, Math.round(dp * context.getResources().getDisplayMetrics().density));
    }

    public void setDisplayMode(int viewMode, int thumbnailSize) {
        boolean changed = this.viewMode != viewMode || this.thumbnailSize != thumbnailSize;
        this.viewMode = viewMode;
        this.thumbnailSize = thumbnailSize;
        if (changed) {
            gridFileNameHeights.clear();
            lastGridSpanCount = -1;
            lastGridContentWidth = -1;
            notifyDataSetChanged();
            scheduleGridRelayout();
        }
    }

    public int getViewMode() {
        return viewMode;
    }

    public int getThumbnailSize() {
        return thumbnailSize;
    }

    public boolean canRegenerateVideoThumbnail(FileItem item) {
        return thumbnailRepository.supportsVideoThumbnail(item);
    }

    public void regenerateVideoThumbnail(FileItem item,
                                         ThumbnailRegenerationCallback callback) {
        if (!canRegenerateVideoThumbnail(item)) {
            callback.onThumbnailRegenerated(false);
            return;
        }

        String requestKey = thumbnailRepository.getRequestKey(item);
        thumbnailRepository.regenerateVideoThumbnail(item, getThumbnailTargetPx(), bitmap -> {
            if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                thumbnailAspectRatios.put(
                        requestKey,
                        bitmap.getHeight() / (float) bitmap.getWidth());
            } else {
                thumbnailAspectRatios.remove(requestKey);
            }

            int position = files.indexOf(item);
            if (position >= 0) {
                notifyItemChanged(position);
            }
            callback.onThumbnailRegenerated(bitmap != null);
        });
    }

    @Override
    public int getItemCount() {
        return files == null ? 0 : files.size();
    }

    public void disableFileOptions() {
        optionsDisabled = true;
    }

    public List<FileItem> getCurrentContent() {
        return new ArrayList<>(files);
    }

    public void clear() {
        if (files == null) {
            return;
        }
        int count = files.size();
        files.clear();
        gridFileNameHeights.clear();
        isInSelectMode = false;
        if (!selectedItems.isEmpty()) {
            selectedItems.clear();
            listener.onFileDeselected();
        }
        notifyItemRangeRemoved(0, count);
    }

    public void newData(List<FileItem> data) {
        // A directory change is a complete data-set replacement. Publishing it as
        // remove-all followed by insert-all makes RecyclerView run a pre-layout with
        // the previous directory's variable row heights. When thumbnails then shrink
        // from square placeholders to their real ratios, those old chunk heights can
        // survive as blank bands. Replace the list atomically instead.
        files = new ArrayList<>(data);
        gridFileNameHeights.clear();
        isInSelectMode = false;
        if (!selectedItems.isEmpty()) {
            selectedItems.clear();
            listener.onFileDeselected();
        }
        showEmptyState(files.isEmpty());
        notifyDataSetChanged();
        scheduleGridRelayout();
    }

    public void updateData(List<FileItem> data) {
        gridFileNameHeights.clear();
        List<FileItem> newData = new ArrayList<>(data);

        if (viewMode == VIEW_MODE_GRID) {
            // Silent refreshes commonly follow a cached directory render. Treat the
            // refreshed result as one geometry transaction too; otherwise remove/insert
            // notifications can briefly combine cached row heights with newly decoded
            // thumbnail ratios and recreate the same permanent gap.
            int selectedCountBefore = selectedItems.size();
            selectedItems.retainAll(newData);
            isInSelectMode = !selectedItems.isEmpty();
            files = newData;
            showEmptyState(files.isEmpty());
            notifyDataSetChanged();
            scheduleGridRelayout();
            if (selectedItems.size() != selectedCountBefore) {
                listener.onFileDeselected();
            }
            return;
        }

        if (newData.isEmpty()) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
            showEmptyState(true);
            return;
        }
        showEmptyState(false);
        List<FileItem> diff = new ArrayList<>(files);

        diff.removeAll(newData);
        for (FileItem fileItem : diff) {
            int index = files.indexOf(fileItem);
            files.remove(index);
            if (selectedItems.contains(fileItem)) {
                selectedItems.remove(fileItem);
                isInSelectMode = !selectedItems.isEmpty();
                listener.onFileDeselected();
            }
            notifyItemRemoved(index);
        }

        diff = new ArrayList<>(data);
        diff.removeAll(files);
        for (FileItem fileItem : diff) {
            int index = newData.indexOf(fileItem);
            files.add(index, fileItem);
            notifyItemInserted(index);
        }

        // FileItem.equals() intentionally identifies an entry only by remote/path/name.
        // A silent directory refresh can therefore keep an older object whose size, modified
        // time or MIME type changed. Replace retained rows with their fresh objects and rebind
        // them so thumbnail eligibility and request keys use current metadata.
        for (int index = 0; index < files.size(); index++) {
            FileItem currentItem = files.get(index);
            int refreshedIndex = newData.indexOf(currentItem);
            if (refreshedIndex < 0) {
                continue;
            }
            FileItem refreshedItem = newData.get(refreshedIndex);
            if (currentItem != refreshedItem) {
                files.set(index, refreshedItem);
                notifyItemChanged(index);
            }
        }

    }

    public void updateSortedData(List<FileItem> data) {
        gridFileNameHeights.clear();
        files = new ArrayList<>(data);
        showEmptyState(files.isEmpty());
        // Sorting changes row membership for most items. One atomic refresh avoids a
        // predictive pass that combines old row heights with the new ordering.
        notifyDataSetChanged();
        scheduleGridRelayout();
    }

    public void refreshData() {
        notifyDataSetChanged();
        scheduleGridRelayout();
    }

    public void setMoveMode(Boolean mode) {
        isInMoveMode = mode;
    }

    public void setSearchMode(Boolean mode) {
        isInSearchMode = mode;
    }

    public void setSelectedItems(List<FileItem> selectedItems) {
        this.selectedItems = new ArrayList<>(selectedItems);
        this.isInSelectMode = true;
        notifyDataSetChanged();
    }

    public Boolean isInSelectMode() {
        return isInSelectMode;
    }

    public List<FileItem> getSelectedItems() {
        return new ArrayList<>(selectedItems);
    }

    public int getNumberOfSelectedItems() {
        return selectedItems.size();
    }

    public Boolean isInMoveMode() {
        return isInMoveMode;
    }

    public void setWrapFileNames(boolean wrapFileNames) {
        this.wrapFileNames = wrapFileNames;
        refreshData();
    }

    private void showEmptyState(Boolean show) {
        if (isInSearchMode) {
            noSearchResultsView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        } else {
            emptyView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void onClickAction(FileItem item, int position) {
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        if (item.isDir() && listener != null) {
            listener.onDirectoryClicked(item, position);
        } else if (!item.isDir() && !isInMoveMode && listener != null) {
            listener.onFileClicked(item);
        }
    }

    public void toggleSelectAll() {
        if (files == null) {
            return;
        }
        if (selectedItems.size() == files.size()) {
            isInSelectMode = false;
            selectedItems.clear();
            listener.onFileDeselected();
        } else {
            isInSelectMode = true;
            selectedItems.clear();
            selectedItems.addAll(files);
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    public void cancelSelection() {
        isInSelectMode = false;
        selectedItems.clear();
        listener.onFileDeselected();
        notifyDataSetChanged();
    }

    public void setCanSelect(Boolean canSelect) {
        this.canSelect = canSelect;
    }

    private int getSelectionBackgroundColor() {
        return context.getColor(R.color.selectedItem);
    }

    private void onLongClickAction(FileItem item, ViewHolder holder) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
            holder.view.setBackgroundColor(Color.TRANSPARENT);
            if (selectedItems.isEmpty()) {
                isInSelectMode = false;
                listener.onFileDeselected();
            }
            listener.onFileDeselected();
        } else {
            selectedItems.add(item);
            isInSelectMode = true;
            holder.view.setBackgroundColor(getSelectionBackgroundColor());
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        public final View view;
        public final View icons;
        public final ImageView fileIcon;
        public final ImageView dirIcon;
        public final View fileNameContainer;
        public final TextView fileName;
        public final TextView fileModTime;
        public final TextView fileSize;
        public final TextView interpunct;
        public final ImageButton fileOptions;
        public final boolean grid;
        public FileItem fileItem;
        private ThumbnailRepository.Cancellable thumbnailRequest;

        ViewHolder(View itemView, boolean grid) {
            super(itemView);
            this.view = itemView;
            this.grid = grid;
            this.icons = view.findViewById(R.id.icons);
            this.fileIcon = view.findViewById(R.id.file_icon);
            this.dirIcon = view.findViewById(R.id.dir_icon);
            this.fileNameContainer = view.findViewById(R.id.file_name_container);
            this.fileName = view.findViewById(R.id.file_name);
            this.fileModTime = view.findViewById(R.id.file_modtime);
            this.fileSize = view.findViewById(R.id.file_size);
            this.fileOptions = view.findViewById(R.id.file_options);
            this.interpunct = view.findViewById(R.id.interpunct);
        }

        private void cancelThumbnailRequest() {
            if (thumbnailRequest != null) {
                thumbnailRequest.cancel();
                thumbnailRequest = null;
            }
        }
    }
}
