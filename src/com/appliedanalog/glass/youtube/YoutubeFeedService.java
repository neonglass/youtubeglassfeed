/*
    GlassHUD - Heads Up Display for Google Glass
    Copyright (C) 2013 James Betker

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.appliedanalog.glass.youtube;

import java.util.UUID;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.glass.location.GlassLocationManager;
import com.google.glass.timeline.TimelineHelper;
import com.google.glass.timeline.TimelineProvider;
import com.google.glass.util.SettingsSecure;
import com.google.googlex.glass.common.proto.MenuItem;
import com.google.googlex.glass.common.proto.MenuValue;
import com.google.googlex.glass.common.proto.TimelineItem;

/**
 * A persistant service that maintains a card on the Glass timeline. When turned
 * "on", this service pushes sensor data from a variety of sources onto this card.
 * @author betker
 *
 */
public class YoutubeFeedService extends Service{
	static final String TAG = "GlassHUDService";
	final int TIMELINE_UPDATE_INTERVAL = 250; //in ms
	
	YoutubeFeedService me;
    
	//States
    boolean enabled = false;
	
	@Override
	public void onCreate(){
		super.onCreate();
		me = this;
		//For the card service
        GlassLocationManager.init(this);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startid){
		super.onStartCommand(intent, flags, startid);
		return START_STICKY;
	}
	
	/**
	 * The Binder class for interfacing between the controller activity and this service.
	 * @author betker
	 *
	 */
	public class ServiceBinder extends Binder{
		public boolean running(){
			return enabled;
		}
		
		public void startup(){
			startHUD();
		}
		
		public void shutdown(){
			stopHUD();
		}
	}
	
	ServiceBinder vBinder = new ServiceBinder();
	@Override
	public IBinder onBind(Intent intent) {
		return vBinder;
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
		stopHUD();
	}

	/**
	 * Starts up the sensors/bluetooth connection and begins pushing data to the timeline.
	 */
	void startHUD(){
		Log.v(TAG, "STARTING YOUTUBE FEED SERVICE");
		if(enabled) return;
		enabled = true;
	}
	
	/**
	 * Shuts down sensors/bluetooth.
	 */
	void stopHUD(){
		Log.v(TAG, "STOPPING YOUTUBE FEED SERVICE");
		if(!enabled) return;
		enabled = false;
	}
	
	String HTML_B4_IMG = "<article class=\"photo\">\n  <img src=\"";
	String HTML_B4_TXT = "\" width=\"100%\" height=\"100%\">\n  <div class=\"photo-overlay\"></div><section><p class=\"text-auto-size\">";
	String HTML_FOOT = "</p></section></article>";
	void pushCard(String videoId){
    	ContentResolver cr = getContentResolver();
    	//For some reason an TimelineHelper instance is required to call some methods.
    	final TimelineHelper tlHelper = new TimelineHelper();
    	
		Log.v(TAG, "YoutubeFeed: pushing a card for " + videoId);
    	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me, new SettingsSecure(cr));
    	ntib.setTitle("Glass HUD");
    	//add the delete menu option
    	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.DELETE).setId(UUID.randomUUID().toString()).build());
    	//add the 'view video' option - only works if you have 
    	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.VIEW_WEB_SITE).setId(UUID.randomUUID().toString())
    										  .addValue(MenuValue.newBuilder().setDisplayName("View Video").build()).build());
    	ntib.setSendToPhoneUrl("https://www.youtube.com/watch?v=" + videoId);
    	String html = HTML_B4_IMG + "http://img.youtube.com/vi/<insert-youtube-video-id-here>/hqdefault.jpg" + 
    				  HTML_B4_TXT + "Youtube Video" + HTML_FOOT;
    	ntib.setHtml(html);
    	
    	TimelineItem item = ntib.build();
    	ContentValues vals = TimelineHelper.toContentValues(item);
    	cr.insert(TimelineProvider.TIMELINE_URI, vals);
	}
}
