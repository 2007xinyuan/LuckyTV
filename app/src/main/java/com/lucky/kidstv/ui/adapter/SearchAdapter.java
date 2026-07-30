package com.lucky.kidstv.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.lucky.kidstv.R;
import com.lucky.kidstv.api.ApiConfig;
import com.lucky.kidstv.bean.Movie;
import com.lucky.kidstv.util.HawkConfig;
import com.lucky.kidstv.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import me.jessyan.autosize.utils.AutoSizeUtils;
public class SearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    public SearchAdapter() {
        super(Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0 ? R.layout.item_search_lite : R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        // lite
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0) {
            helper.setText(R.id.tvName, String.format("%s  %s %s %s", ApiConfig.get().getSource(item.sourceKey).getName(), item.name, item.type == null ? "" : item.type, item.note == null ? "" : item.note));
        } else {// with preview
            helper.setText(R.id.tvName, item.name);
            helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
            helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
            if (item.note != null && !item.note.isEmpty()) {
                helper.setText(R.id.tvNote, item.note);
            }
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            if (!TextUtils.isEmpty(item.pic)) {
                // takagen99 : Use Glide instead
                ImgUtil.load(item.pic, ivThumb, 14);
            } else {
                ivThumb.setImageResource(R.drawable.img_loading_placeholder);
            }
        }
    }
}
