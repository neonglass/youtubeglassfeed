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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;

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
	final String HOME_CARD = "HomeCardIdent";
	
	YoutubeFeedService me;
	String feedUrl = "http://gdata.youtube.com/feeds/api/standardfeeds/top_rated";
	int updateInterval = 10 * 60 * 1000; //every 10 minutes
	boolean sendAllVideos = true; //everytime there is a new video, send all the videos from the feed as opposed to just one.
    
	//States
    boolean enabled = true;
	
	@Override
	public void onCreate(){
		super.onCreate();
		me = this;
		
		//For the card service
        GlassLocationManager.init(this);
	}
	
	//Intent extras
	final String FEED_SERVICE_OP = "op";
	
	//And values
	final int OP_SYNC = 5832;
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startid){
		super.onStartCommand(intent, flags, startid);
		loadConfiguration(); //reload config every cycle.
		
		if(intent.getData() != null){
			Log.v(TAG, "onStartCommand! intent=" + intent.getData().toString());
		}
		
		//If the service was started with a purpose, handle it.
		if(intent != null && intent.getExtras() != null &&
		   intent.getExtras().getInt(FEED_SERVICE_OP, -1) != -1){
			switch(intent.getExtras().getInt(FEED_SERVICE_OP)){
			case OP_SYNC:
				//We need to do a sync, but since it is a network operation we need to push it off the main thread.
				(new Thread(){
					public void run(){
						parseFeed();
					}
				}).start();
				break;
			}
			return START_NOT_STICKY;
		}
		
		//Fetch the enable state
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		enabled = prefs.getBoolean(SERVICE_ENABLED, true);
		if(enabled){
			startFeed();
		}
		
		String homeCardId = prefs.getString(HOME_CARD, null);
		if(homeCardId == null){
			//Push the introduction card.
			pushIntroductionCard();
		}
		
		return START_NOT_STICKY;
	}
	
	void loadConfiguration(){
		//Attempt to load configuration settings if they exist
		try{
			BufferedReader reader = new BufferedReader(new FileReader(CONFIGURATION_FILE));
			String line = "";
			while((line = reader.readLine()) != null){
				try{
					if(line.startsWith("#")) continue; //its a comment
					if(line.startsWith("youtubeFeed=")){
						feedUrl = line.replace("youtubeFeed=", "");
						Log.v(TAG, "Setting feedUrl=" + feedUrl);
					}
					if(line.startsWith("queryInterval=")){
						updateInterval = Integer.parseInt(line.replace("queryInterval=", "")) * 60 * 1000;
						Log.v(TAG, "Setting queryInterval=" + updateInterval + "ms");
					}
					if(line.startsWith("sendAllVideos=")){
						sendAllVideos = Boolean.parseBoolean(line.replace("sendAllVideos=", ""));
						Log.v(TAG, "Setting sendAllVideos=" + sendAllVideos);
					}
				}catch(Exception ex){
					Log.v(TAG, "Improper configuration formatting on this line: `" + line + "` - " + ex.getMessage());
				}
			}
			Log.v(TAG, "Successfully parsed configuration file. youtubeFeed='" + feedUrl + "', queryInterval='" + updateInterval + "'");
			reader.close();
		}catch(Exception e){
			Log.v(TAG, "Error loading configuration file: " + e.getMessage());
		}
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
	}

	PendingIntent getAlarmIntent(){
    	Intent i = new Intent(this, YoutubeFeedService.class);
    	i.putExtra(FEED_SERVICE_OP, OP_SYNC);
    	PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
    	return pi;
	}
	
	/**
	 * Starts up the sensors/bluetooth connection and begins pushing data to the timeline.
	 */
	void startFeed(){
		Log.v(TAG, "STARTING YOUTUBE FEED SERVICE");
		
    	//create recurring intent
    	AlarmManager mgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
		PendingIntent pi = getAlarmIntent();
		//turn off autosync if it has been started
    	mgr.cancel(pi);
		//turn it on now.
    	mgr.setRepeating(AlarmManager.RTC_WAKEUP, SystemClock.elapsedRealtime(), updateInterval, pi);
    	
		enabled = true;
		//Commit to prefs.
		saveEnableState();
		
		//Update home card
		refreshIntroductionCard();
	}
	
	/**
	 * Shuts down sensors/bluetooth.
	 */
	void stopFeed(){
		Log.v(TAG, "STOPPING YOUTUBE FEED SERVICE");
		
    	//halt the timer that is doing the updating.
    	AlarmManager mgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
		PendingIntent pi = getAlarmIntent();
		//turn off autosync
    	mgr.cancel(pi);
    	
		enabled = false;
		saveEnableState();
		
		//Update home card
		refreshIntroductionCard();
	}
	
	void saveEnableState(){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putBoolean(SERVICE_ENABLED, enabled);
		ed.commit();
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
	
	void parseFeed(){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String lastPostedVideoId = prefs.getString(LAST_VIDEO_ID, "");
		
		Log.v(TAG, "Parsing feed: " + feedUrl);
		
		try{
			SyndFeedInput input = new SyndFeedInput();
			SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));
			Log.v(TAG, "Feed contains " + feed.getEntries().size() + " items. Last Vid ID=" + lastPostedVideoId);
			
			//This list will hold all of the IDs in the RSS stream, which we will then iterate to deliver. We have to do two iterations to make
			//sure we don't re-deliver movies and such.
			ArrayList<VideoInfo> vids = new ArrayList<VideoInfo>();
			Iterator entries = feed.getEntries().iterator();
			boolean hasNewVideos = true; boolean firstEntry = true; boolean hitEnd = false;
			while(entries.hasNext()){
				SyndEntry entry = (SyndEntry)entries.next();
				String link = entry.getLink(); //this will be something like youtube.com/watch?v=<id>&featuer=blahblahblah, we just want the id
				String id = link.substring(link.indexOf("watch?v=") + 8);
				if(id.contains("&")){
					id = id.substring(0, id.indexOf("&"));
				}
				if(id.equals("")){
					Log.v(TAG, "Error parsing video title.." + entry.getLink());
				}
				if(firstEntry && lastPostedVideoId != null && lastPostedVideoId.equals(id)){
					Log.v(TAG, "Feed does not have any new videos");
					hasNewVideos = false;
					break;
				}
				firstEntry = false;
				if(lastPostedVideoId != null && lastPostedVideoId.equals(id)){
					if(!hitEnd){
						Log.v(TAG, "Hit the last processed video in the feed. " + vids.size() + " new videos found.");
						hitEnd = true;
					}
					if(!sendAllVideos){ //then do not include any more videos in the array list we will be sending off to the cards.
						break; //we've hit the point in the feed where we already were at, don't repost cards.
					}
				}
				vids.add(new VideoInfo(id, entry.getTitle(), fetchVideoDurationFromContents(entry.getContents())));
			}
			
			//Last but not least, make sure we save the latest video we received back into the shared prefs.
			if(hasNewVideos){
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
			Log.v(TAG, "Failed to fetch feed");
			rss.printStackTrace();
		}
		
		//stop the service, it is no longer needed.
		stopSelf();
	}
	
	

	void pushCards(ArrayList<VideoInfo> vidInfos){	
		final String HTML_B4_IMG = "<article class=\"photo\">\n  <img src=\"";
		final String HTML_B4_TXT = "\" width=\"100%\" height=\"100%\">\n  <div class=\"photo-overlay\"></div><section><p class=\"text-auto-size\">";
		final String HTML_FOOT = "</p></section></article>";
		
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
        	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me, new SettingsSecure(cr));
        	ntib.setTitle("YouTube Feed");
        	//add the 'view video' option - only works if you have the youtube app installed
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
        		ntib.setNotification(NotificationConfig.newBuilder().setLevel(NotificationConfig.Level.DEFAULT)); //Doesn't work, probably because we are sideloading the card
        	}
        	cards.add(ntib.build());
        	firstCard = false;
    	}
		
    	//Bulk insert the cards
    	tlHelper.bulkInsertTimelineItem(me, cards);
	}
	
	void applyHomeCardValues(TimelineItem.Builder tibuilder){
		final String HTML_B4_UPDATE_INTERVAL = "<article> <section> <b>YouTube Feed for Glass</b><br><div class=\"text-small\" style=\"padding-top:15px;\">Updates Every ";
		final String HTML_B4_FEED_URL = " minutes</div><div class=\"text-small\" style=\"padding-top:5px;\">Feed:</div><div class=\"blue text-x-small\">";
		final String HTML_FOOT = "</div></section></article>";
		final String HTML_NOT_ENABLED = "<article> <section> <b>YouTube Feed for Glass</b><br /><b class=\"red\">Turned Off</b></section></article>";
		
		String url, text, html;
		if(enabled){
			url = "youtubefeedservice://stopFeed";
			text = "Stop feed";
			html = HTML_B4_UPDATE_INTERVAL + (updateInterval / 1000 / 60) + HTML_B4_FEED_URL + feedUrl + HTML_FOOT;
			
			//pin status.
			tibuilder.setIsPinned(false);
			tibuilder.setPinTime(-1); //unpinned pin time  	
		}else{
			url = "youtubefeedservice://startFeed";
			text = "Start feed";
			html = HTML_NOT_ENABLED;
			
			//pin status.
			tibuilder.setIsPinned(true);
			tibuilder.setPinTime(System.currentTimeMillis()); //unpinned pin time  	
		}
    	//custom URL that this app will intercept.
		tibuilder.setSendToPhoneUrl(url);
		tibuilder.clearMenuItem();
		tibuilder.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.VIEW_WEB_SITE).setId(UUID.randomUUID().toString())
    										       .addValue(MenuValue.newBuilder().setDisplayName(text).build()).build());
    	//delete option
		tibuilder.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.DELETE).setId(UUID.randomUUID().toString()).build());
		
		//HTML
    	tibuilder.setHtml(html);
	}

	void pushIntroductionCard(){	
    	ContentResolver cr = getContentResolver();
    	//For some reason an TimelineHelper instance is required to call some methods.
    	final TimelineHelper tlHelper = new TimelineHelper();
    	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me,  new SettingsSecure(cr));
    	ntib.setTitle("YouTube Feeds");
    	applyHomeCardValues(ntib);
    	TimelineItem card = ntib.build();
    	ContentValues vals = TimelineHelper.toContentValues(card);
    	cr.insert(TimelineProvider.TIMELINE_URI, vals);
    	
    	//Store the ID for later use
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(me);
    	SharedPreferences.Editor editor = pref.edit();
    	editor.putString(HOME_CARD, card.getId());
    	editor.commit();
	}
	
	/**
	 * changes the home card to reflect the current state of the system.
	 */
	void refreshIntroductionCard(){
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(me);   
		String homeCardId = pref.getString(HOME_CARD, null);
		if(homeCardId == null){ //no point going further
			return;
		}
		
    	final TimelineHelper tlHelper = new TimelineHelper();
    	final ContentResolver cr = getContentResolver();
		final TimelineItem baseItem = tlHelper.queryTimelineItem(cr, homeCardId);
		TimelineHelper.Update updater = new TimelineHelper.Update(){
			@Override
			public TimelineItem onExecute() {
	    		TimelineItem.Builder builder = TimelineItem.newBuilder(baseItem);
	    		applyHomeCardValues(builder);
	    		
	    		//also, pin this card so that the service can be re-started in the future.
	    		
	    		
	    		//I still haven't figured out quite what the last two booleans here do..
	    		return tlHelper.updateTimelineItem(me, builder.build(), null, true, false);
			}
		};
		//Send the callback off to the thread. This should block until the update is complete.
		TimelineHelper.atomicUpdateTimelineItem(updater);
	}
}
