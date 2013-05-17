/*
    Youtube Feed - YouTube Atom Feed for Google Glass
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

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import com.google.glass.logging.UserEventAction;
import com.google.glass.timeline.TimelineHelper;
import com.google.glass.timeline.TimelineProvider;
import com.google.glass.util.SettingsSecure;
import com.google.googlex.glass.common.proto.MenuItem;
import com.google.googlex.glass.common.proto.MenuValue;
import com.google.googlex.glass.common.proto.NotificationConfig;
import com.google.googlex.glass.common.proto.TimelineItem;
import com.sun.syndication.feed.synd.SyndContent;
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
	final String CONFIGURATION_FILE = "/sdcard/.youtubefeedconfig";
	
	//Shared pref constants
	final String LAST_VIDEO_ID = "LastVideoFed";
	final String SERVICE_ENABLED = "ServiceEnabled";
	
	YoutubeFeedService me;
	FeedParser feedParser; //see below for def.
	String feedUrl = "http://gdata.youtube.com/feeds/api/standardfeeds/top_rated";
	int updateInterval = 10 * 60 * 1000; //every 10 minutes
    
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
		
		//Attempt to load configuration settings if they exist
		try{
			BufferedReader reader = new BufferedReader(new FileReader(CONFIGURATION_FILE));
			String line = "";
			while((line = reader.readLine()) != null){
				if(line.startsWith("#")) continue; //its a comment
				if(line.startsWith("youtubeFeed=")){
					feedUrl = line.replace("youtubeFeed=", "");
				}
				if(line.startsWith("queryInterval=")){
					updateInterval = Integer.parseInt(line.replace("queryInterval=", "")) * 60 * 1000;
				}
			}
			Log.v(TAG, "Successfully parsed configuration file. youtubeFeed='" + feedUrl + "', queryInterval='" + updateInterval + "'");
			reader.close();
		}catch(Exception e){
			Log.v(TAG, "Error loading configuration file: " + e.getMessage());
		}
		
		//Fetch the enable state
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		enabled = prefs.getBoolean(SERVICE_ENABLED, false);
		if(enabled){
			startFeed();
		}
		
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
		if(!feedParser.running) (new Thread(feedParser)).start();
		enabled = true;
		//Commit to prefs.
		saveEnableState();
	}
	
	/**
	 * Shuts down sensors/bluetooth.
	 */
	void stopFeed(){
		Log.v(TAG, "STOPPING YOUTUBE FEED SERVICE");
		if(feedParser.running) feedParser.stop();
		enabled = false;
		saveEnableState();
	}
	
	void saveEnableState(){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putBoolean(SERVICE_ENABLED, enabled);
		ed.commit();
	}
	
	class FeedParser implements Runnable{
		boolean running = false;
		public void run(){
			running = true;
			while(running){
				parseFeed();
				try{
					Thread.sleep(updateInterval);
				}catch(Exception e){}
			}
		}
		public void stop(){
			running = false;
		}
	}

	//structure for holding title and id
	class VideoInfo{
		String id, title, duration;
		public VideoInfo(String i, String tit, String dur){
			id = i; title = tit; duration = dur;
		}
	}
	
	String fetchVideoDurationFromContents(List contents){
		//fetch the video duration from the content if we can find it
		final String TIME_DENOTE = "Time:</span>";
		Iterator contiter = contents.iterator();
		while(contiter.hasNext()){
			SyndContent content = (SyndContent)contiter.next();
			if(content.getValue().contains(TIME_DENOTE)){
				//This is the one we *probably* want to parse.
				try{
					String duration = content.getValue().substring(content.getValue().indexOf(TIME_DENOTE) + TIME_DENOTE.length());
					//now we are left with something like this: `<span style="color: #000000; font-size: 11px; font-weight: bold;">39:52</span></td> ...{crud}`
					//cut off the last part first.
					if(duration.contains("</span>")){
						duration = duration.substring(0, duration.indexOf("</span>"));
						//and cut out just the time.
						int index = duration.lastIndexOf('>');
						if(index == -1){
							break;
						}
						duration = duration.substring(index + 1); //should have the final string now.
						if(duration.length() > 12){ //This is a final sanity check
							break;
						}
						return duration;
					}
				}catch(Exception e){
					e.printStackTrace();
				}
				break; //in case we fell through
			}
		}
		return "";
	}
	
	ArrayList<String> parseFeed(){
		Log.v(TAG, "Parsing feed.");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String lastPostedVideoId = prefs.getString(LAST_VIDEO_ID, "");
		
		try{
			SyndFeedInput input = new SyndFeedInput();
			SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));
			Log.v(TAG, "Feed contains " + feed.getEntries().size() + " items. Last Vid ID=" + lastPostedVideoId);
			
			//This list will hold all of the IDs in the RSS stream, which we will then iterate to deliver. We have to do two iterations to make
			//sure we don't re-deliver movies and such.
			ArrayList<VideoInfo> vids = new ArrayList<VideoInfo>();
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
				vids.add(new VideoInfo(id, entry.getTitle(), fetchVideoDurationFromContents(entry.getContents())));		
			}
			Log.v(TAG, "Only " + vids.size() + " are new videos.");
			
			//Last but not least, make sure we save the latest video we received back into the shared prefs.
			if(vids.size() > 0){
				//Push the videos to the timeline
				pushCards(vids);
				//Save the newest card as the latest read video.
				VideoInfo latestVid = vids.get(0);
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(LAST_VIDEO_ID, latestVid.id);
				ed.commit();
				Log.v(TAG, "Saving last read video id: " + latestVid.id);
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
	void pushCards(ArrayList<VideoInfo> vidInfos){
    	ContentResolver cr = getContentResolver();
    	//For some reason an TimelineHelper instance is required to call some methods.
    	final TimelineHelper tlHelper = new TimelineHelper();
    	
    	String bundleId = UUID.randomUUID().toString();
    	ArrayList<TimelineItem> cards = new ArrayList<TimelineItem>();
    	
    	//Iterate through all of the new videos coming in and add them to the same bundle. We are iterating in reverse so that
    	//the newest video gets pushed to the top of the stack.
		ListIterator<VideoInfo> iter = vidInfos.listIterator(vidInfos.size());
    	boolean firstCard = true;
    	while(iter.hasPrevious()){
    		VideoInfo vidInfo = iter.previous();
    		Log.v(TAG, "YoutubeFeed: pushing a card for " + vidInfo.id);
        	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me, new SettingsSecure(cr));
        	ntib.setTitle("YouTube Feed");
        	//add the 'view video' option - only works if you have 
        	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.VIEW_WEB_SITE).setId(UUID.randomUUID().toString())
        										  .addValue(MenuValue.newBuilder().setDisplayName("View Video").build()).build());
        	//add the delete menu option
        	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.DELETE).setId(UUID.randomUUID().toString()).build());
        	ntib.setSendToPhoneUrl("https://www.youtube.com/watch?v=" + vidInfo.id);
        	ntib.setText(vidInfo.title + " (" + vidInfo.duration + ")");
        	String html = HTML_B4_IMG + "http://img.youtube.com/vi/" + vidInfo.id + "/hqdefault.jpg" + 
        				  HTML_B4_TXT + ntib.getText() + HTML_FOOT;
        	ntib.setHtml(html);
        	ntib.setBundleId(bundleId);
        	if(firstCard){
        		ntib.setNotification(NotificationConfig.newBuilder().setLevel(NotificationConfig.Level.DEFAULT));
        	}
        	cards.add(ntib.build());
        	firstCard = false;
    	}
		
    	//Bulk insert the cards
    	tlHelper.bulkInsertTimelineItem(me, cards);
	}
}
