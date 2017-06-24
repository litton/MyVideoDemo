package com.zxmark.videodownloader.fragment;

import android.app.ProgressDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.facebook.ads.Ad;
import com.facebook.ads.AdError;
import com.facebook.ads.AdListener;
import com.facebook.ads.NativeAd;
import com.imobapp.videodownloaderforinstagram.R;
import com.zxmark.videodownloader.adapter.ItemViewHolder;
import com.zxmark.videodownloader.adapter.MainDownloadingRecyclerAdapter;
import com.zxmark.videodownloader.bean.VideoBean;
import com.zxmark.videodownloader.db.DBHelper;
import com.zxmark.videodownloader.db.DownloadContentItem;
import com.zxmark.videodownloader.db.DownloaderDBHelper;
import com.zxmark.videodownloader.downloader.DownloadingTaskList;
import com.zxmark.videodownloader.downloader.VideoDownloadFactory;
import com.zxmark.videodownloader.service.DownloadService;
import com.zxmark.videodownloader.util.Globals;
import com.zxmark.videodownloader.util.LogUtil;
import com.zxmark.videodownloader.util.PreferenceUtils;
import com.zxmark.videodownloader.util.URLMatcher;
import com.zxmark.videodownloader.widget.IToast;

import java.util.List;

// In the Activity that will launch the native ad,
// implement the AdListener interface and add the following:

/**
 * Created by fanlitao on 17/6/13.
 */

public class DownloadingFragment extends Fragment implements View.OnClickListener, MainDownloadingRecyclerAdapter.IBtnCallback {


    private EditText mUrlEditText;
    private Button mDownloadBtn;
    private RecyclerView mListView;
    private LinearLayoutManager mLayoutManager;
    private MainDownloadingRecyclerAdapter mAdapter;
    private List<DownloadContentItem> mDataList;
    private ProgressDialog mProgressDialog;

    private View mHowToView;

    public String mReceiveUrlParams;

    private NativeAd nativeAd;

    private View mFacebookAdViewContainer;
    private RequestManager mGlide;
    private DownloadContentItem mFirstAdBean;

    private DownloadContentItem mHowToBean = null;

    private boolean isShowHowToPage;


    public static DownloadingFragment newInstance(String params) {
        DownloadingFragment fragment = new DownloadingFragment();
        fragment.mReceiveUrlParams = params;
        return fragment;
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.downloading_page, container, false);

        return view;
    }

    private View findViewById(int id) {
        return getView().findViewById(id);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mGlide = Glide.with(getActivity());

        mFacebookAdViewContainer = findViewById(R.id.main_ad_container);
        mListView = (RecyclerView) findViewById(R.id.downloading_list);
        mListView.setHasFixedSize(true);
        mLayoutManager = new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL,
                false);
        mListView.setLayoutManager(mLayoutManager);
        mListView.setItemAnimator(new DefaultItemAnimator());

        DownloadingTaskList.SINGLETON.getExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                mDataList = DownloaderDBHelper.SINGLETON.getDownloadingTask();
                DownloadContentItem headerBean = new DownloadContentItem();
                headerBean.itemType = DownloadContentItem.TYPE_HEADER_ITEM;
                mDataList.add(0, headerBean);
                if (PreferenceUtils.isFirstRunMainFragment()) {
                    isShowHowToPage = true;
                    mHowToBean = new DownloadContentItem();
                    mHowToBean.itemType = DownloadContentItem.TYPE_HOWTO_ITEM;
                    mDataList.add(mHowToBean);
                }

                if (isAdded()) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mAdapter = new MainDownloadingRecyclerAdapter(mDataList, true, DownloadingFragment.this);
                            mListView.setAdapter(mAdapter);
                            showNativeAd();
                        }
                    });
                }
            }
        });


        if (!TextUtils.isEmpty(mReceiveUrlParams)) {
            receiveSendAction(mReceiveUrlParams);
        }

    }

    public void receiveSendAction(String url) {
        startDownload(url);
    }

    private void startDownload(final String url) {
        if (isAdded()) {
            String pageURL = URLMatcher.getHttpURL(url);
            if (VideoDownloadFactory.getInstance().isSupportWeb(pageURL)) {
                showCheckURLProgressDialog();
                Intent intent = new Intent(getActivity(), DownloadService.class);
                intent.setAction(DownloadService.REQUEST_VIDEO_URL_ACTION);
                intent.putExtra(DownloadService.EXTRAS_FLOAT_VIEW, false);
                intent.putExtra(Globals.EXTRAS, url);
                getActivity().startService(intent);
            } else {
                IToast.makeText(getActivity(), R.string.not_support_url, Toast.LENGTH_SHORT).show();
            }
        }

    }

    private void showCheckURLProgressDialog() {
        if (isAdded()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mProgressDialog = ProgressDialog.show(getActivity(), getActivity().getString(R.string.eheck_url_dialgo_title), getActivity().getString(R.string.check_url), true, true);
                    mProgressDialog.show();
                }
            });
        }

    }

    public void publishProgress(final String pageURL, final int filePosition, final int progress) {
        if (getActivity() != null && isAdded()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    DownloadContentItem bean = new DownloadContentItem();
                    bean.pageURL = pageURL;
                    if (mDataList != null) {
                        int index = mDataList.indexOf(bean);
                        if (index > -1) {
                            DownloadContentItem downloadContentItem = mDataList.get(index);
                            RecyclerView.ViewHolder viewHolder = mListView.findViewHolderForAdapterPosition(index);
                            if (viewHolder != null && viewHolder instanceof ItemViewHolder) {
                                ItemViewHolder itemHolder = (ItemViewHolder) viewHolder;
                                itemHolder.progressBar.setVisibility(View.VISIBLE);
                                itemHolder.taskCountView.setText(getResources().getString(R.string.downloading_left_task_count, downloadContentItem.fileCount - filePosition));
                                int count = downloadContentItem.fileCount * 100;
                                int position = filePosition;
                                int totalProgress = position * 100 + progress;
                                int newProgrees = totalProgress * 100 / count;
                                itemHolder.progressBar.setProgress(newProgrees);
                                if (newProgrees >= 100) {
                                    itemHolder.progressBar.setVisibility(View.GONE);
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * receive new task
     */
    public void onReceiveNewTask(String pageURL) {
        if (isAdded()) {
            if (mProgressDialog != null && mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
                mProgressDialog = null;
            }
            if (TextUtils.isEmpty(pageURL)) {
                return;
            }

            if(pageURL.equals(getString(R.string.toast_downlaoded_video))) {
                return;
            }
            DownloadContentItem videoBean = DownloaderDBHelper.SINGLETON.getDownloadItemByPageURL(pageURL);
            LogUtil.e("downloading", "receiveNewTask=" + pageURL);
            if (videoBean != null) {
                mDataList.add(1, videoBean);
                mAdapter.notifyItemInserted(1);
            }
        }
    }

    public void onStartDownload(String pageURL) {
        LogUtil.e("main", "onStartDownload111=" + pageURL);
        if (isAdded()) {
            if (mProgressDialog != null && mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
                mProgressDialog = null;
            }
            if (TextUtils.isEmpty(pageURL)) {
                IToast.makeText(getActivity(), R.string.spider_request_error, Toast.LENGTH_SHORT).show();
                return;
            }

            DownloadContentItem bean = new DownloadContentItem();
            bean.pageURL = pageURL;
            if (!mDataList.contains(bean)) {
                DownloadContentItem videoBean = DownloaderDBHelper.SINGLETON.getDownloadItemByPageURL(pageURL);
                if (videoBean != null) {
                    mDataList.add(1, videoBean);
                    mAdapter.notifyItemInserted(1);
                }

                showNativeAd();
            }
        }
    }

    public void deleteVideoByPath(String pageURL) {
        if (TextUtils.isEmpty(pageURL)) {
            return;
        }
        DownloadContentItem bean = new DownloadContentItem();
        bean.pageURL = pageURL;
        if (mDataList.contains(bean)) {
            mAdapter.notifyItemRemoved(mDataList.indexOf(bean));
            mDataList.remove(bean);

        }
    }

    @Override
    public void onClick(View v) {
    }


    public void showHotToInfo() {
        if (isShowHowToPage) {
            isShowHowToPage = false;
            mDataList.remove(mHowToBean);
            mAdapter.notifyDataSetChanged();
        } else {
            isShowHowToPage = true;
            if (mHowToBean == null) {
                mHowToBean = new DownloadContentItem();
                mHowToBean.itemType = DownloadContentItem.TYPE_HOWTO_ITEM;
            }
            mDataList.add(1, mHowToBean);
            mAdapter.notifyDataSetChanged();
        }
    }


    private void showNativeAd() {
        if (isAdded()) {
            if (mFirstAdBean != null) {
                return;
            }
            if (mDataList != null && (mDataList.size() == 1 || mDataList.size() > 3)) {
                nativeAd = new NativeAd(getActivity(), "2099565523604162_2099565860270795");
                nativeAd.setAdListener(new AdListener() {
                    @Override
                    public void onError(Ad ad, AdError adError) {
                        LogUtil.v("facebook", "onError:" + adError);
                    }

                    @Override
                    public void onAdLoaded(Ad ad) {
                        onFacebookAdLoaded(ad);
                    }

                    @Override
                    public void onAdClicked(Ad ad) {

                    }

                    @Override
                    public void onLoggingImpression(Ad ad) {

                    }
                });

                nativeAd.loadAd();
            }
        }
    }

    // The next step is to extract the ad metadata and use its properties
// to build your customized native UI. Modify the onAdLoaded function
// above to retrieve the ad properties. For example:
    public void onFacebookAdLoaded(Ad ad) {
        if (getActivity() == null || isDetached()) {
            return;
        }
        if (ad != nativeAd) {
            return;
        }


        mFirstAdBean = new DownloadContentItem();
        mFirstAdBean.itemType = DownloadContentItem.TYPE_FACEBOOK_AD;
        mFirstAdBean.facebookNativeAd = nativeAd;
        if (mDataList == null) {
            return;
        }
        if (mDataList.size() == 1) {
            mDataList.add(mFirstAdBean);
            mAdapter.notifyDataSetChanged();
        } else {
            int position = mDataList.size() / 2;
            mDataList.add(position, mFirstAdBean);
            mAdapter.notifyItemInserted(position);
        }

    }

    @Override
    public void showHowTo() {
        showHotToInfo();
    }

    @Override
    public void onDownloadFromClipboard() {
        final ClipboardManager cb = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        String pastUrl = cb.getText().toString();
        if (!TextUtils.isEmpty(pastUrl)) {
            startDownload(pastUrl);
        }
    }
}
