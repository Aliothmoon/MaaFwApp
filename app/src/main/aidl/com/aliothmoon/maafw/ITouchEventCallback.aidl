// ITouchEventCallback.aidl
package com.aliothmoon.maafw;

// Declare any non-default types here with import statements

oneway interface ITouchEventCallback {
   void onCallback(int x, int y, int type, int contact);
}