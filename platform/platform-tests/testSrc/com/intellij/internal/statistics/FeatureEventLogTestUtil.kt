// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.internal.statistics

import com.intellij.internal.statistic.eventLog.*

fun newEvent(recorderId: String,
             type: String,
             time: Long = System.currentTimeMillis(),
             session: String = "session-id",
             build: String = "999.999",
             recorderVersion: String = "1",
             bucket: String = "-1",
             count: Int = 1,
             data: Map<String, Any> = emptyMap()): LogEvent {
  val action = LogEventAction(type)
  action.count = count
  val event = newLogEvent(session, build, bucket, time, recorderId, recorderVersion, action)
  for (datum in data) {
    event.event.addData(datum.key, datum.value)
  }
  return event
}

fun newStateEvent(recorderId: String,
                  type: String,
                  time: Long = System.currentTimeMillis(),
                  session: String = "session-id",
                  build: String = "999.999",
                  recorderVersion: String = "1",
                  bucket: String = "-1",
                  data: Map<String, Any> = emptyMap()): LogEvent {
  val event = newLogEvent(session, build, bucket, time, recorderId, recorderVersion, type, true)
  for (datum in data) {
    event.event.addData(datum.key, datum.value)
  }
  return event
}