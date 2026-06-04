package com.example.galaxymirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkTransportDetector(context: Context) {
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?

  fun currentTransport(): StreamNetworkTransport {
    val manager = connectivityManager ?: return StreamNetworkTransport.OTHER
    val network = manager.activeNetwork ?: return StreamNetworkTransport.OTHER
    val capabilities = manager.getNetworkCapabilities(network) ?: return StreamNetworkTransport.OTHER
    return when {
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> StreamNetworkTransport.WIFI
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> StreamNetworkTransport.CELLULAR
      else -> StreamNetworkTransport.OTHER
    }
  }
}
