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

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.UUID;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.glass.location.GlassLocationManager;
import com.google.glass.timeline.TimelineHelper;
import com.google.glass.timeline.TimelineProvider;
import com.google.glass.util.SettingsSecure;
import com.google.googlex.glass.common.proto.MenuItem;
import com.google.googlex.glass.common.proto.MenuValue;
import com.google.googlex.glass.common.proto.TimelineItem;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

/**
 * A persistant service that maintains a card on the Glass timeline. When turned
 * "on", this service pushes sensor data from a variety of sources onto this card.
 * @author betker
 *
 */
public class YoutubeFeedService extends Service{
	static final String TAG = "YoutubeFeedService";
	
	//This is just constant because I have no good way of making it configurable in glass.
	final String FEED_TO_PARSE = "http://gdata.youtube.com/feeds/base/users/neonbjb/newsubscriptionvideos";
	final int UPDATE_INTERVAL = 120000; //every 2 minutes
	
	YoutubeFeedService me;
	FeedParser feedParser; //see below for def.
    
	//States
    boolean enabled = false;
	
	@Override
	public void onCreate(){
		super.onCreate();
		me = this;
		//For the card service
        GlassLocationManager.init(this);
        feedParser = new FeedParser();
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
			startFeed();
		}
		
		public void shutdown(){
			stopFeed();
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
		stopFeed();
	}

	/**
	 * Starts up the sensors/bluetooth connection and begins pushing data to the timeline.
	 */
	void startFeed(){
		Log.v(TAG, "STARTING YOUTUBE FEED SERVICE");
		if(enabled) return;
		(new Thread(feedParser)).start();
		enabled = true;
	}
	
	/**
	 * Shuts down sensors/bluetooth.
	 */
	void stopFeed(){
		Log.v(TAG, "STOPPING YOUTUBE FEED SERVICE");
		if(!enabled) return;
		feedParser.stop();
		enabled = false;
	}
	
	class FeedParser implements Runnable{
		boolean running = false;
		public void run(){
			running = true;
			while(running){
				parseFeed();
				try{
					Thread.sleep(UPDATE_INTERVAL);
				}catch(Exception e){}
			}
		}
		public void stop(){
			running = false;
		}
	}

	//structure for holding title and id
	class VidIdAndTitle{
		String id, title;
		public VidIdAndTitle(String i, String tit){
			id = i; title = tit;
		}
	}
	
	final String LAST_VIDEO_ID = "LastVideoFed";
	ArrayList<String> parseFeed(){
		Log.v(TAG, "Parsing feed.");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String lastPostedVideoId = prefs.getString(LAST_VIDEO_ID, "");
		
		try{
			SyndFeedInput input = new SyndFeedInput();
			SyndFeed feed = input.build(new XmlReader(new URL(FEED_TO_PARSE)));
			Log.v(TAG, "Feed contains " + feed.getEntries().size() + " items");
			
			//This list will hold all of the IDs in the RSS stream, which we will then iterate to deliver. We have to do two iterations to make
			//sure we don't re-deliver movies and such.
			ArrayList<VidIdAndTitle> vids = new ArrayList<VidIdAndTitle>();
			Iterator entries = feed.getEntries().iterator();
			while(entries.hasNext()){
				SyndEntry entry = (SyndEntry)entries.next();
				String link = entry.getLink(); //this will be something like youtube.com/watch?v=<id>&featuer=blahblahblah, we just want the id
				String id = link.substring(link.indexOf("watch?v=") + 8);
				if(id.contains("&")){
					id = id.substring(0, id.indexOf("&"));
				}
				if(lastPostedVideoId != null && lastPostedVideoId.equals(id)){
					break; //we've hit the point in the feed where we already were at, don't repost cards.
				}
				vids.add(new VidIdAndTitle(id, entry.getTitle()));
			}
			Log.v(TAG, "Only " + vids.size() + " are new videos.");
			
			//Now iterate vids in reverse, so that the oldest movies get put on the timeline first.
			ListIterator<VidIdAndTitle> iter = vids.listIterator(vids.size());
			VidIdAndTitle vid = null;
			while(iter.hasPrevious()){
				vid = iter.previous();
				pushCard(vid.title, vid.id);
			}
			//Last but not least, make sure we save the latest video we received back into the shared prefs.
			if(vid != null){
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(LAST_VIDEO_ID, vid.id);
				ed.commit();
			}
		}catch(Exception rss){
			Log.v(TAG, "Failed to fetch feed.");
			rss.printStackTrace();
		}
		return null;
	}
	
	String HTML_B4_IMG = "<article class=\"photo\">\n  <img src=\"";
	String HTML_B4_TXT = "\" width=\"100%\" height=\"100%\">\n  <div class=\"photo-overlay\"></div><section><p class=\"text-auto-size\">";
	String HTML_FOOT = "</p></section></article>";
	void pushCard(String vidTitle, String videoId){
    	ContentResolver cr = getContentResolver();
    	//For some reason an TimelineHelper instance is required to call some methods.
    	final TimelineHelper tlHelper = new TimelineHelper();
    	
		Log.v(TAG, "YoutubeFeed: pushing a card for " + videoId);
    	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me, new SettingsSecure(cr));
    	ntib.setTitle("Glass HUD");
    	//add the 'view video' option - only works if you have 
    	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.VIEW_WEB_SITE).setId(UUID.randomUUID().toString())
    										  .addValue(MenuValue.newBuilder().setDisplayName("View Video").build()).build());
    	//add the delete menu option
    	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.DELETE).setId(UUID.randomUUID().toString()).build());
    	ntib.setSendToPhoneUrl("https://www.youtube.com/watch?v=" + videoId);
    	String html = HTML_B4_IMG + "http://img.youtube.com/vi/" + videoId + "/hqdefault.jpg" + 
    				  HTML_B4_TXT + vidTitle + HTML_FOOT;
    	ntib.setHtml(html);
    	
    	TimelineItem item = ntib.build();
    	ContentValues vals = TimelineHelper.toContentValues(item);
    	cr.insert(TimelineProvider.TIMELINE_URI, vals);
	}
}
