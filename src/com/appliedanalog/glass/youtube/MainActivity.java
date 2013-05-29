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
 * The MainActivity for this app is simply a launcher for the backing service. It has no UI.
 * @author betker
 */
public class MainActivity extends Activity {
	final String TAG = "MainActivity";
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	}
	
	@Override
	public void onStart(){
		super.onStart();
		
		//Start up the service.
		Intent serviceIntent = new Intent(this, YoutubeFeedService.class);
		Intent myIntent = getIntent();
		if(myIntent != null){
			serviceIntent.setData(myIntent.getData());
		}
		startService(serviceIntent);
		finish();
	}

	@Override
	public void onStop(){
		super.onStop();
	}
}
