package com.tofi.mapboxdemo

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

@SuppressLint("LogNotTimber")
class MainActivity : AppCompatActivity() ,
    PermissionsListener {
    private lateinit var mapView: MapView
    private lateinit var map: MapboxMap
    private lateinit var startButton: Button
    private lateinit var permissionsManager:PermissionsManager
    private var currentRoute:DirectionsRoute? = null
    private lateinit var locationComponent:LocationComponent
    private var navigationMapRoute: NavigationMapRoute? = null
    private val mLocationPermissionGranted = false
    private var mFusedLocationClient: FusedLocationProviderClient? = null

    companion object{
        const val TAG = "MainActivity"
        const val DESTINATION_ICON_ID = "destination-icon-id"
        const val DESTINATION_SOURCE_ID = "destination-source-id"
        const val DESTINATION_SYMBOL_LAYER_ID = "destination-symbol-layer-id"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.secret_access_token));
        setContentView(R.layout.activity_main);
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mapView = findViewById(R.id.mapView);
        startButton = findViewById(R.id.startButton)
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap;
            map.setStyle(Style.MAPBOX_STREETS) { style ->
                enableLocationComponent(style);

                addDestinationIconSymbolLayer(style);
                map.addOnMapClickListener { point ->
                    val destinationPoint: Point? =
                        Point.fromLngLat(point.longitude, point.latitude)
                    val originPoint: Point? = locationComponent.lastKnownLocation?.longitude?.let {
                        Point.fromLngLat(
                            it,
                            locationComponent.lastKnownLocation?.latitude!!
                        )
                    }

                    map.style?.getSourceAs<GeoJsonSource?>(
                        DESTINATION_SOURCE_ID
                    )?.setGeoJson(Feature.fromGeometry(destinationPoint))

                    if (originPoint != null) {
                        if (destinationPoint != null) {
                            getRoute(originPoint, destinationPoint)
                        }
                    }
                    startButton.isEnabled = true
                    startButton.setBackgroundResource(R.color.mapboxBlue)
                    return@addOnMapClickListener true
                }
                startButton.setOnClickListener {
                    //Start Navigation
                    val simulateRoute = true
                    val options = NavigationLauncherOptions.builder()
                        .directionsRoute(currentRoute)
                        .shouldSimulateRoute(simulateRoute)
                        .build()
                    NavigationLauncher.startNavigation(this, options)
                }

            }
        }
        getLastKnownLocation()
    }
    private fun getLastKnownLocation() {
        Log.d(TAG, "getLastKnownLocation: called.")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        mFusedLocationClient?.lastLocation?.addOnSuccessListener {
            Log.d(TAG, "getLastKnownLocation: $it ")
            startLocationService()
        }
//        mFusedLocationClient!!.lastLocation.addOnCompleteListener { task ->
//            if (task.isSuccessful) {
//                val location: Location = task.result
//                //                    val geoPoint = GeoPoint(location.getLatitude(), location.getLongitude())
//                //                    mUserLocation.setGeo_point(geoPoint)
//                //                    mUserLocation.setTimestamp(null)
//                //                    saveUserLocation()
//            }
//        }
    }
    private fun startLocationService() {
        if (!isLocationServiceRunning()) {
            val serviceIntent = Intent(this, LocationService::class.java)
            //        this.startService(serviceIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isLocationServiceRunning(): Boolean {
        val manager: ActivityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if ("com.tofi.mapboxdemo.LocationService" == service.service.className) {
                Log.d(
                    TAG,
                    "isLocationServiceRunning: location service is already running."
                )
                return true
            }
        }
        Log.d(TAG, "isLocationServiceRunning: location service is not running.")
        return false
    }

    private fun addDestinationIconSymbolLayer(loadedMapStyle: Style){
        loadedMapStyle.addImage(
            DESTINATION_ICON_ID,
            BitmapFactory.decodeResource(resources, R.drawable.mapbox_marker_icon_default)
        )
        val geoJsonSource = GeoJsonSource(DESTINATION_SOURCE_ID)
        loadedMapStyle.addSource(geoJsonSource)
        val destinationSymbolLayer = SymbolLayer(DESTINATION_SYMBOL_LAYER_ID, DESTINATION_SOURCE_ID)
        destinationSymbolLayer.withProperties(
            iconImage(DESTINATION_ICON_ID),
            iconAllowOverlap(true),
            iconIgnorePlacement(true)
        )
        loadedMapStyle.addLayer(destinationSymbolLayer)
    }

    private fun getRoute(origin: Point, destination: Point) {
        NavigationRoute.builder(this)
            .accessToken(Mapbox.getAccessToken()!!)
            .origin(origin)
            .destination(destination)
            .build()
            .getRoute(object : Callback<DirectionsResponse?> {
                @SuppressLint("LogNotTimber")
                override fun onResponse(
                    call: Call<DirectionsResponse?>?,
                    response: Response<DirectionsResponse?>
                ) {
                    // You can get the generic HTTP info about the response
                    Log.d(TAG, "Response code: " + response.code())
                    if (response.body() == null) {
                        Log.e(
                            TAG,
                            "No routes found, make sure you set the right user and access token."
                        )
                        return
                    } else if (response.body()?.routes()?.size!! < 1) {
                        Log.e(TAG, "No routes found")
                        return
                    }
                    currentRoute = response.body()?.routes()?.get(0)

                    // Draw the route on the map
                    if (navigationMapRoute != null) {
                        navigationMapRoute?.removeRoute()
                    } else {
                        navigationMapRoute =
                            NavigationMapRoute(null, mapView, map, R.style.NavigationMapRoute)
                    }
                    navigationMapRoute?.addRoute(currentRoute)
                }

                override fun onFailure(call: Call<DirectionsResponse?>?, throwable: Throwable) {
                    Log.e(TAG, "Error: " + throwable.message)
                }
            })
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style){
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            // Activate the MapboxMap LocationComponent to show user location
            // Adding in LocationComponentOptions is also an optional parameter
            locationComponent = map.locationComponent;
            locationComponent.activateLocationComponent(this, loadedMapStyle);
            locationComponent.isLocationComponentEnabled = true;
// Set the component's camera mode
            locationComponent.cameraMode = CameraMode.TRACKING;
        } else {
            permissionsManager = PermissionsManager(this);
            permissionsManager.requestLocationPermissions(this);
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()

    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }



    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        Toast.makeText(this, R.string.user_location_permission_explanation, Toast.LENGTH_LONG).show();
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            map.style?.let { enableLocationComponent(it) };
        } else {
            Toast.makeText(this, R.string.user_location_permission_not_granted, Toast.LENGTH_LONG).show();
            finish();
        }
    }


}