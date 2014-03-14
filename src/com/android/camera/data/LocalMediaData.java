/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.data;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.camera.Storage;
import com.android.camera.util.CameraUtil;
import com.android.camera2.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A base class for all the local media files. The bitmap is loaded in
 * background thread. Subclasses should implement their own background loading
 * thread by sub-classing BitmapLoadTask and overriding doInBackground() to
 * return a bitmap.
 */
public abstract class LocalMediaData implements LocalData {
    /** The minimum id to use to query for all media at a given media store uri */
    static final int QUERY_ALL_MEDIA_ID = -1;
    private static final String CAMERA_PATH = Storage.DIRECTORY + "%";
    private static final String SELECT_BY_PATH = MediaStore.MediaColumns.DATA + " LIKE ?";

    protected final long mContentId;
    protected final String mTitle;
    protected final String mMimeType;
    protected final long mDateTakenInSeconds;
    protected final long mDateModifiedInSeconds;
    protected final String mPath;
    // width and height should be adjusted according to orientation.
    protected final int mWidth;
    protected final int mHeight;
    protected final long mSizeInBytes;
    protected final double mLatitude;
    protected final double mLongitude;
    protected final Bundle mMetaData;

    /**
     * Used for thumbnail loading optimization. True if this data has a
     * corresponding visible view.
     */
    protected Boolean mUsing = false;

    public LocalMediaData(long contentId, String title, String mimeType,
            long dateTakenInSeconds, long dateModifiedInSeconds, String path,
            int width, int height, long sizeInBytes, double latitude,
            double longitude) {
        mContentId = contentId;
        mTitle = new String(title);
        mMimeType = new String(mimeType);
        mDateTakenInSeconds = dateTakenInSeconds;
        mDateModifiedInSeconds = dateModifiedInSeconds;
        mPath = new String(path);
        mWidth = width;
        mHeight = height;
        mSizeInBytes = sizeInBytes;
        mLatitude = latitude;
        mLongitude = longitude;
        mMetaData = new Bundle();
    }

    private interface CursorToLocalData {
        public LocalData build(Cursor cursor);
    }

    private static List<LocalData> queryLocalMediaData(ContentResolver contentResolver,
            Uri contentUri, String[] projection, long minimumId, String orderBy,
            CursorToLocalData builder) {
        String selection = SELECT_BY_PATH + " AND " + MediaStore.MediaColumns._ID + " > ?";
        String[] selectionArgs = new String[] { CAMERA_PATH, Long.toString(minimumId) };

        Cursor cursor = contentResolver.query(contentUri, projection,
                selection, selectionArgs, orderBy);
        List<LocalData> result = new ArrayList<LocalData>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                LocalData data = builder.build(cursor);
                if (data != null) {
                    result.add(data);
                } else {
                    final int dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                    Log.e(TAG, "Error loading data:"
                            + cursor.getString(dataIndex));
                }
            }
        }
        return result;
    }

    @Override
    public long getDateTaken() {
        return mDateTakenInSeconds;
    }

    @Override
    public long getDateModified() {
        return mDateModifiedInSeconds;
    }

    @Override
    public long getContentId() {
        return mContentId;
    }

    @Override
    public String getTitle() {
        return new String(mTitle);
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @Override
    public int getRotation() {
        return 0;
    }

    @Override
    public String getPath() {
        return mPath;
    }

    @Override
    public long getSizeInBytes() {
        return mSizeInBytes;
    }

    @Override
    public boolean isUIActionSupported(int action) {
        return false;
    }

    @Override
    public boolean isDataActionSupported(int action) {
        return false;
    }

    @Override
    public boolean delete(Context context) {
        File f = new File(mPath);
        return f.delete();
    }

    @Override
    public void onFullScreen(boolean fullScreen) {
        // do nothing.
    }

    @Override
    public boolean canSwipeInFullScreen() {
        return true;
    }

    protected ImageView fillImageView(Context context, ImageView v,
            int decodeWidth, int decodeHeight, Drawable placeHolder,
            LocalDataAdapter adapter, boolean isInProgress) {
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        if (placeHolder != null) {
            v.setImageDrawable(placeHolder);
        }

        // TODO: Load MediaStore or embedded-in-JPEG-stream thumbnail.

        BitmapLoadTask task = getBitmapLoadTask(context, v, decodeWidth, decodeHeight,
                context.getContentResolver(), adapter, isInProgress);
        task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        return v;
    }

    @Override
    public View getView(Context context, int decodeWidth, int decodeHeight, Drawable placeHolder,
            LocalDataAdapter adapter, boolean isInProgress) {
        return fillImageView(context, new ImageView(context), decodeWidth, decodeHeight,
                placeHolder, adapter, isInProgress);
    }

    @Override
    public void resizeView(Context context, int width, int height, View view,
                           LocalDataAdapter adapter) {
        // Default is do nothing.
        // Can be implemented by sub-classes.
    }

    @Override
    public void prepare() {
        synchronized (mUsing) {
            mUsing = true;
        }
    }

    @Override
    public void recycle() {
        synchronized (mUsing) {
            mUsing = false;
        }
    }

    @Override
    public double[] getLatLong() {
        if (mLatitude == 0 && mLongitude == 0) {
            return null;
        }
        return new double[] {
                mLatitude, mLongitude
        };
    }

    protected boolean isUsing() {
        synchronized (mUsing) {
            return mUsing;
        }
    }

    @Override
    public String getMimeType() {
        return mMimeType;
    }

    @Override
    public MediaDetails getMediaDetails(Context context) {
        DateFormat dateFormatter = DateFormat.getDateTimeInstance();
        MediaDetails mediaDetails = new MediaDetails();
        mediaDetails.addDetail(MediaDetails.INDEX_TITLE, mTitle);
        mediaDetails.addDetail(MediaDetails.INDEX_WIDTH, mWidth);
        mediaDetails.addDetail(MediaDetails.INDEX_HEIGHT, mHeight);
        mediaDetails.addDetail(MediaDetails.INDEX_PATH, mPath);
        mediaDetails.addDetail(MediaDetails.INDEX_DATETIME,
                dateFormatter.format(new Date(mDateModifiedInSeconds * 1000)));
        if (mSizeInBytes > 0) {
            mediaDetails.addDetail(MediaDetails.INDEX_SIZE, mSizeInBytes);
        }
        if (mLatitude != 0 && mLongitude != 0) {
            String locationString = String.format(Locale.getDefault(), "%f, %f", mLatitude,
                    mLongitude);
            mediaDetails.addDetail(MediaDetails.INDEX_LOCATION, locationString);
        }
        return mediaDetails;
    }

    @Override
    public abstract int getViewType();

    @Override
    public Bundle getMetadata() {
        return mMetaData;
    }

    @Override
    public boolean isMetadataUpdated() {
        return MetadataLoader.isMetadataLoaded(this);
    }

    /**
     * A background task that loads the provided ImageView with a Bitmap.
     * A Bitmap of maximum size that fits into a decodeWidth x decodeHeight
     * box will be decoded.
     */
    protected abstract BitmapLoadTask getBitmapLoadTask(
            Context context, ImageView v, int decodeWidth, int decodeHeight,
            ContentResolver resolver, LocalDataAdapter adapter, boolean isInProgressSession);

    public static final class PhotoData extends LocalMediaData {
        private static final String TAG = "PhotoData";

        public static final int COL_ID = 0;
        public static final int COL_TITLE = 1;
        public static final int COL_MIME_TYPE = 2;
        public static final int COL_DATE_TAKEN = 3;
        public static final int COL_DATE_MODIFIED = 4;
        public static final int COL_DATA = 5;
        public static final int COL_ORIENTATION = 6;
        public static final int COL_WIDTH = 7;
        public static final int COL_HEIGHT = 8;
        public static final int COL_SIZE = 9;
        public static final int COL_LATITUDE = 10;
        public static final int COL_LONGITUDE = 11;

        // GL max texture size: keep bitmaps below this value.
        private static final int MAXIMUM_TEXTURE_SIZE = 2048;
        // Maximum pixel count for Bitmaps.  To limit RAM consumption.
        private static final int MAXIMUM_DECODE_PIXELS = 4000000;

        static final Uri CONTENT_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        private static final String QUERY_ORDER = MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC, "
                + MediaStore.Images.ImageColumns._ID + " DESC";
        /**
         * These values should be kept in sync with column IDs (COL_*) above.
         */
        private static final String[] QUERY_PROJECTION = {
                MediaStore.Images.ImageColumns._ID,           // 0, int
                MediaStore.Images.ImageColumns.TITLE,         // 1, string
                MediaStore.Images.ImageColumns.MIME_TYPE,     // 2, string
                MediaStore.Images.ImageColumns.DATE_TAKEN,    // 3, int
                MediaStore.Images.ImageColumns.DATE_MODIFIED, // 4, int
                MediaStore.Images.ImageColumns.DATA,          // 5, string
                MediaStore.Images.ImageColumns.ORIENTATION,   // 6, int, 0, 90, 180, 270
                MediaStore.Images.ImageColumns.WIDTH,         // 7, int
                MediaStore.Images.ImageColumns.HEIGHT,        // 8, int
                MediaStore.Images.ImageColumns.SIZE,          // 9, long
                MediaStore.Images.ImageColumns.LATITUDE,      // 10, double
                MediaStore.Images.ImageColumns.LONGITUDE      // 11, double
        };

        private static final int mSupportedUIActions = ACTION_DEMOTE | ACTION_PROMOTE | ACTION_ZOOM;
        private static final int mSupportedDataActions =
                DATA_ACTION_DELETE | DATA_ACTION_EDIT | DATA_ACTION_SHARE;

        /** from MediaStore, can only be 0, 90, 180, 270 */
        private final int mOrientation;

        public PhotoData(long id, String title, String mimeType,
                long dateTakenInSeconds, long dateModifiedInSeconds,
                String path, int orientation, int width, int height,
                long sizeInBytes, double latitude, double longitude) {
            super(id, title, mimeType, dateTakenInSeconds, dateModifiedInSeconds,
                    path, width, height, sizeInBytes, latitude, longitude);
            mOrientation = orientation;

        }

        static List<LocalData> query(ContentResolver cr, Uri uri, long lastId) {
            return queryLocalMediaData(cr, uri, QUERY_PROJECTION, lastId, QUERY_ORDER,
                    new PhotoDataBuilder());
        }

        private static PhotoData buildFromCursor(Cursor c) {
            long id = c.getLong(COL_ID);
            String title = c.getString(COL_TITLE);
            String mimeType = c.getString(COL_MIME_TYPE);
            long dateTakenInSeconds = c.getLong(COL_DATE_TAKEN);
            long dateModifiedInSeconds = c.getLong(COL_DATE_MODIFIED);
            String path = c.getString(COL_DATA);
            int orientation = c.getInt(COL_ORIENTATION);
            int width = c.getInt(COL_WIDTH);
            int height = c.getInt(COL_HEIGHT);
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Zero dimension in ContentResolver for "
                        + path + ":" + width + "x" + height);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, opts);
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth;
                    height = opts.outHeight;
                } else {
                    Log.w(TAG, "Dimension decode failed for " + path);
                    Bitmap b = BitmapFactory.decodeFile(path);
                    if (b == null) {
                        Log.w(TAG, "PhotoData skipped."
                                + " Decoding " + path + "failed.");
                        return null;
                    }
                    width = b.getWidth();
                    height = b.getHeight();
                    if (width == 0 || height == 0) {
                        Log.w(TAG, "PhotoData skipped. Bitmap size 0 for " + path);
                        return null;
                    }
                }
            }

            long sizeInBytes = c.getLong(COL_SIZE);
            double latitude = c.getDouble(COL_LATITUDE);
            double longitude = c.getDouble(COL_LONGITUDE);
            PhotoData result = new PhotoData(id, title, mimeType, dateTakenInSeconds,
                    dateModifiedInSeconds, path, orientation, width, height,
                    sizeInBytes, latitude, longitude);
            return result;
        }

        @Override
        public int getRotation() {
            return mOrientation;
        }

        @Override
        public String toString() {
            return "Photo:" + ",data=" + mPath + ",mimeType=" + mMimeType
                    + "," + mWidth + "x" + mHeight + ",orientation=" + mOrientation
                    + ",date=" + new Date(mDateTakenInSeconds);
        }

        @Override
        public int getViewType() {
            return VIEW_TYPE_REMOVABLE;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return ((action & mSupportedUIActions) == action);
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return ((action & mSupportedDataActions) == action);
        }

        @Override
        public boolean delete(Context context) {
            ContentResolver cr = context.getContentResolver();
            cr.delete(CONTENT_URI, MediaStore.Images.ImageColumns._ID + "=" + mContentId, null);
            return super.delete(context);
        }

        @Override
        public Uri getContentUri() {
            Uri baseUri = CONTENT_URI;
            return baseUri.buildUpon().appendPath(String.valueOf(mContentId)).build();
        }

        @Override
        public MediaDetails getMediaDetails(Context context) {
            MediaDetails mediaDetails = super.getMediaDetails(context);
            MediaDetails.extractExifInfo(mediaDetails, mPath);
            mediaDetails.addDetail(MediaDetails.INDEX_ORIENTATION, mOrientation);
            return mediaDetails;
        }

        @Override
        public int getLocalDataType() {
            return LOCAL_IMAGE;
        }

        @Override
        public LocalData refresh(Context context) {
            PhotoData newData = null;
            Cursor c = context.getContentResolver().query(getContentUri(), QUERY_PROJECTION, null,
                    null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    newData = buildFromCursor(c);
                }
                c.close();
            }

            return newData;
        }

        @Override
        public void resizeView(Context context, int w, int h, View v, LocalDataAdapter adapter)
        {
            // This will call PhotoBitmapLoadTask.
            fillImageView(context, (ImageView) v, w, h, null, adapter, false);
        }

        @Override
        protected BitmapLoadTask getBitmapLoadTask(Context context, ImageView v, int decodeWidth,
                int decodeHeight, ContentResolver resolver, LocalDataAdapter adapter,
                boolean isInProgressSession) {
            return new PhotoBitmapLoadTask(context, v, decodeWidth, decodeHeight, resolver, adapter,
                    isInProgressSession);
        }

        @Override
        public boolean rotate90Degrees(Context context, LocalDataAdapter adapter,
                int currentDataId, boolean clockwise) {
            RotationTask task = new RotationTask(context, adapter,
                    currentDataId, clockwise);
            task.execute(this);
            return true;
        }

        private final class PhotoBitmapLoadTask extends BitmapLoadTask {
            private final int mDecodeWidth;
            private final int mDecodeHeight;
            private final Context mContext;
            private final LocalDataAdapter mAdapter;

            // TODO: Re-think how we can avoid having the in-progress indication
            // here.
            private final boolean mIsInProgressSession;

            private boolean mNeedsRefresh;

            public PhotoBitmapLoadTask(Context context, ImageView v, int decodeWidth,
                    int decodeHeight, ContentResolver resolver, LocalDataAdapter adapter,
                    boolean isInProgressSession) {
                super(context, v);
                mDecodeWidth = decodeWidth;
                mDecodeHeight = decodeHeight;
                mContext = context;
                mAdapter = adapter;
                mIsInProgressSession = isInProgressSession;
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                // TODO: Implement image cache, which can verify image dims.

                // For correctness, double check image size here.
                // This only takes 1% of full decode time.
                Point decodedSize = LocalDataUtil.decodeBitmapDimension(mPath);

                // If the width and height are valid and not matching the values
                // from MediaStore, then update the MediaStore. This only
                // happens when the MediaStore has been told incorrect values.
                if (decodedSize.x > 0 && decodedSize.y > 0 &&
                        (decodedSize.x != mWidth || decodedSize.y != mHeight)) {
                    ContentValues values = new ContentValues();
                    values.put(Images.Media.WIDTH, decodedSize.x);
                    values.put(Images.Media.HEIGHT, decodedSize.y);
                    mContext.getContentResolver().update(getContentUri(), values, null, null);
                    mNeedsRefresh = true;
                    Log.w(TAG, "Uri " + getContentUri() + " has been updated with" +
                            " the correct size!");
                    return null;
                }

                InputStream stream;
                Bitmap bitmap;
                try {
                    stream = new FileInputStream(mPath);
                    bitmap = LocalDataUtil
                            .loadImageThumbnailFromStream(stream, mWidth, mHeight, mDecodeWidth,
                                    mDecodeHeight, mOrientation, MAXIMUM_DECODE_PIXELS);
                    stream.close();
                } catch (FileNotFoundException e) {
                    Log.v(TAG, "File not found:" + mPath);
                    bitmap = null;
                } catch (IOException e) {
                    Log.v(TAG, "IOException for " + mPath, e);
                    bitmap = null;
                }

                return bitmap;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                super.onPostExecute(bitmap);
                if (mNeedsRefresh && mAdapter != null) {
                    mAdapter.refresh(getContentUri());
                }
            }
        }

        private static class PhotoDataBuilder implements CursorToLocalData {
            @Override
            public PhotoData build(Cursor cursor) {
                return LocalMediaData.PhotoData.buildFromCursor(cursor);
            }
        }
    }

    public static final class VideoData extends LocalMediaData {
        public static final int COL_ID = 0;
        public static final int COL_TITLE = 1;
        public static final int COL_MIME_TYPE = 2;
        public static final int COL_DATE_TAKEN = 3;
        public static final int COL_DATE_MODIFIED = 4;
        public static final int COL_DATA = 5;
        public static final int COL_WIDTH = 6;
        public static final int COL_HEIGHT = 7;
        public static final int COL_RESOLUTION = 8;
        public static final int COL_SIZE = 9;
        public static final int COL_LATITUDE = 10;
        public static final int COL_LONGITUDE = 11;
        public static final int COL_DURATION = 12;

        static final Uri CONTENT_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        private static final int mSupportedUIActions = ACTION_DEMOTE | ACTION_PROMOTE;
        private static final int mSupportedDataActions =
                DATA_ACTION_DELETE | DATA_ACTION_PLAY | DATA_ACTION_SHARE;

        private static final String QUERY_ORDER = MediaStore.Video.VideoColumns.DATE_TAKEN
                + " DESC, " + MediaStore.Video.VideoColumns._ID + " DESC";
        /**
         * These values should be kept in sync with column IDs (COL_*) above.
         */
        private static final String[] QUERY_PROJECTION = {
                MediaStore.Video.VideoColumns._ID,           // 0, int
                MediaStore.Video.VideoColumns.TITLE,         // 1, string
                MediaStore.Video.VideoColumns.MIME_TYPE,     // 2, string
                MediaStore.Video.VideoColumns.DATE_TAKEN,    // 3, int
                MediaStore.Video.VideoColumns.DATE_MODIFIED, // 4, int
                MediaStore.Video.VideoColumns.DATA,          // 5, string
                MediaStore.Video.VideoColumns.WIDTH,         // 6, int
                MediaStore.Video.VideoColumns.HEIGHT,        // 7, int
                MediaStore.Video.VideoColumns.RESOLUTION,    // 8 string
                MediaStore.Video.VideoColumns.SIZE,          // 9 long
                MediaStore.Video.VideoColumns.LATITUDE,      // 10 double
                MediaStore.Video.VideoColumns.LONGITUDE,     // 11 double
                MediaStore.Video.VideoColumns.DURATION       // 12 long
        };

        /** The duration in milliseconds. */
        private final long mDurationInSeconds;

        public VideoData(long id, String title, String mimeType,
                long dateTakenInSeconds, long dateModifiedInSeconds,
                String path, int width, int height, long sizeInBytes,
                double latitude, double longitude, long durationInSeconds) {
            super(id, title, mimeType, dateTakenInSeconds, dateModifiedInSeconds,
                    path, width, height, sizeInBytes, latitude, longitude);
            mDurationInSeconds = durationInSeconds;
        }

        static List<LocalData> query(ContentResolver cr, Uri uri) {
            return queryLocalMediaData(cr, uri, QUERY_PROJECTION, QUERY_ALL_MEDIA_ID,
                    QUERY_ORDER, new VideoDataBuilder());
        }

        private static VideoData buildFromCursor(Cursor c) {
            long id = c.getLong(COL_ID);
            String title = c.getString(COL_TITLE);
            String mimeType = c.getString(COL_MIME_TYPE);
            long dateTakenInSeconds = c.getLong(COL_DATE_TAKEN);
            long dateModifiedInSeconds = c.getLong(COL_DATE_MODIFIED);
            String path = c.getString(COL_DATA);
            int width = c.getInt(COL_WIDTH);
            int height = c.getInt(COL_HEIGHT);

            // Extracts video height/width if available. If unavailable, set to
            // 0.
            if (width == 0 || height == 0) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                String rotation = null;
                try {
                    retriever.setDataSource(path);
                } catch (RuntimeException ex) {
                    // setDataSource() can cause RuntimeException beyond
                    // IllegalArgumentException. e.g: data contain *.avi file.
                    retriever.release();
                    Log.e(TAG, "MediaMetadataRetriever.setDataSource() fail:"
                            + ex.getMessage());
                    return null;
                }
                rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

                String val = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                width = (val == null) ? 0 : Integer.parseInt(val);
                val = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                height = (val == null) ? 0 : Integer.parseInt(val);
                retriever.release();
                if (width == 0 || height == 0) {
                    // Width or height is still not available.
                    Log.e(TAG, "Unable to retrieve dimension of video:" + path);
                    return null;
                }
                if (rotation != null
                        && (rotation.equals("90") || rotation.equals("270"))) {
                    int b = width;
                    width = height;
                    height = b;
                }
            }

            long sizeInBytes = c.getLong(COL_SIZE);
            double latitude = c.getDouble(COL_LATITUDE);
            double longitude = c.getDouble(COL_LONGITUDE);
            long durationInSeconds = c.getLong(COL_DURATION) / 1000;
            VideoData d = new VideoData(id, title, mimeType, dateTakenInSeconds,
                    dateModifiedInSeconds, path, width, height, sizeInBytes,
                    latitude, longitude, durationInSeconds);
            return d;
        }

        @Override
        public String toString() {
            return "Video:" + ",data=" + mPath + ",mimeType=" + mMimeType
                    + "," + mWidth + "x" + mHeight + ",date=" + new Date(mDateTakenInSeconds);
        }

        @Override
        public int getViewType() {
            return VIEW_TYPE_REMOVABLE;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return ((action & mSupportedUIActions) == action);
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return ((action & mSupportedDataActions) == action);
        }

        @Override
        public boolean delete(Context context) {
            ContentResolver cr = context.getContentResolver();
            cr.delete(CONTENT_URI, MediaStore.Video.VideoColumns._ID + "=" + mContentId, null);
            return super.delete(context);
        }

        @Override
        public Uri getContentUri() {
            Uri baseUri = CONTENT_URI;
            return baseUri.buildUpon().appendPath(String.valueOf(mContentId)).build();
        }

        @Override
        public MediaDetails getMediaDetails(Context context) {
            MediaDetails mediaDetails = super.getMediaDetails(context);
            String duration = MediaDetails.formatDuration(context, mDurationInSeconds);
            mediaDetails.addDetail(MediaDetails.INDEX_DURATION, duration);
            return mediaDetails;
        }

        @Override
        public int getLocalDataType() {
            return LOCAL_VIDEO;
        }

        @Override
        public LocalData refresh(Context context) {
            Cursor c = context.getContentResolver().query(getContentUri(), QUERY_PROJECTION, null,
                    null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            VideoData newData = buildFromCursor(c);
            return newData;
        }

        @Override
        public View getView(final Context context,
                int decodeWidth, int decodeHeight, Drawable placeHolder,
                LocalDataAdapter adapter, boolean isInProgress) {

            // ImageView for the bitmap.
            ImageView iv = new ImageView(context);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            fillImageView(context, iv, decodeWidth, decodeHeight, placeHolder,
                    adapter, isInProgress);

            // ImageView for the play icon.
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.ic_control_play);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            icon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // TODO: refactor this into activities to avoid this class
                    // conversion.
                    CameraUtil.playVideo((Activity) context, getContentUri(), mTitle);
                }
            });

            FrameLayout f = new FrameLayout(context);
            f.addView(iv);
            f.addView(icon);
            return f;
        }

        @Override
        protected BitmapLoadTask getBitmapLoadTask(
                Context context, ImageView v, int decodeWidth, int decodeHeight,
                ContentResolver resolver, LocalDataAdapter adapter, boolean isInProgressSession) {
            // TODO: Support isInProgressSession for videos when we need it.
            return new VideoBitmapLoadTask(context, v);
        }

        private final class VideoBitmapLoadTask extends BitmapLoadTask {

            public VideoBitmapLoadTask(Context context, ImageView v) {
                super(context, v);
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                if (isCancelled() || !isUsing()) {
                    return null;
                }
                Bitmap bitmap = null;
                bitmap = LocalDataUtil.loadVideoThumbnail(mPath);

                if (isCancelled() || !isUsing()) {
                    return null;
                }
                return bitmap;
            }
        }

        @Override
        public boolean rotate90Degrees(Context context, LocalDataAdapter adapter,
                int currentDataId, boolean clockwise) {
            // We don't support rotation for video data.
            Log.e(TAG, "Unexpected call in rotate90Degrees()");
            return false;
        }
    }

    /**
     * An {@link AsyncTask} class that loads the bitmap in the background
     * thread. Sub-classes should implement their own
     * {@code BitmapLoadTask#doInBackground(Void...)}."
     */
    protected abstract class BitmapLoadTask extends AsyncTask<Void, Void, Bitmap> {
        protected final Context mContext;
        protected ImageView mView;

        protected BitmapLoadTask(Context context, ImageView v) {
            mContext = context;
            mView = v;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (!isUsing()) {
                return;
            }
            if (bitmap == null) {
                Log.e(TAG, "Failed decoding bitmap for file:" + mPath);
                return;
            }
            BitmapDrawable d = new BitmapDrawable(mContext.getResources(), bitmap);
            mView.setScaleType(ImageView.ScaleType.FIT_XY);
            mView.setImageDrawable(d);
            Log.v(TAG, "Created bitmap: " + bitmap.getWidth() + " x " + bitmap.getHeight());
        }
    }

    private static class VideoDataBuilder implements CursorToLocalData {

        @Override
        public VideoData build(Cursor cursor) {
            return LocalMediaData.VideoData.buildFromCursor(cursor);
        }
    }

}
