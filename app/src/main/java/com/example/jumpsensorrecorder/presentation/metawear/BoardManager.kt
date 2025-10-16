package com.example.jumpsensorrecorder.metawear

import com.mbientlab.metawear.MetaWearBoard

object BoardManager {
    @Volatile
    var board: MetaWearBoard? = null
}
