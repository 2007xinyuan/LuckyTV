package com.lucky.kidstv.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.lucky.kidstv.cache.Cache;
import com.lucky.kidstv.cache.CacheDao;
import com.lucky.kidstv.cache.SearchDao;
import com.lucky.kidstv.cache.SearchHistory;
import com.lucky.kidstv.cache.StorageDrive;
import com.lucky.kidstv.cache.StorageDriveDao;
import com.lucky.kidstv.cache.VodCollect;
import com.lucky.kidstv.cache.VodCollectDao;
import com.lucky.kidstv.cache.VodRecord;
import com.lucky.kidstv.cache.VodRecordDao;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Database(entities = {Cache.class, VodRecord.class, VodCollect.class, StorageDrive.class, SearchHistory.class}, version = 3)
public abstract class AppDataBase extends RoomDatabase {
    public abstract CacheDao getCacheDao();

    public abstract VodRecordDao getVodRecordDao();

    public abstract VodCollectDao getVodCollectDao();

    public abstract StorageDriveDao getStorageDriveDao();

    public abstract SearchDao getSearchDao();
}
