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

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.widget.Button;
import android.widget.TextView;

/**
 * The MainActivity for this app is simply a controller for the backing Service. The
 * Service then provides the UI through the Glass Timeline. This activity allows the
 * user to turn Service functionality on and off and lock the screen in a permanently
 * on state.
 * @author betker
 */
public class MainActivity extends Activity {
	final String TAG = "MainActivity";

    //UI Elements
	Activity me;
    Button bEnableFeed;
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		//Set up UI
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.activity_main);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
		bEnableFeed = (Button)findViewById(R.id.bEnableFeed);
		me = this;
		
		//And bind actions
		bEnableFeed.setOnClickListener(new OnClickListener(){
			@Override
			public void onClick(View v) {
				if(bound && servBinder.running()){
					servBinder.shutdown();
				}else if(bound){
					servBinder.startup();
				}
				updateTextFields();
			}
		});
	}
	
	private void updateTextFields(){
		if(bound){
			if(servBinder.running()){
				bEnableFeed.setText("Turn off Feed Service");
			}else{
				bEnableFeed.setText("Turn on Feed Service");
			}
		}
	}

	YoutubeFeedService.ServiceBinder servBinder;
	boolean bound = false;
	ServiceConnection mConnection = new ServiceConnection(){
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			Log.v(TAG, "ServiceConnected");
			servBinder = (YoutubeFeedService.ServiceBinder)service;
			bound = true;
			updateTextFields();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.v(TAG, "ServiceDisconnected");
			bound = false;
		}
	};
	
	@Override
	public void onStart(){
		super.onStart();
		startService(new Intent(this, YoutubeFeedService.class));
		Intent sIntent = new Intent(this, YoutubeFeedService.class);
		bindService(sIntent, mConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	public void onStop(){
		super.onStop();
		if(bound){
			this.unbindService(mConnection);
		}
	}
}
