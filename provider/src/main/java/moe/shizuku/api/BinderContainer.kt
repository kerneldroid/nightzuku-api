package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX

@RestrictTo(LIBRARY_GROUP_PREFIX)
class BinderContainer : Parcelable {

    @JvmField
    var binder: IBinder?

    constructor(binder: IBinder?) {
        this.binder = binder
    }

    protected constructor(parcel: Parcel) {
        this.binder = parcel.readStrongBinder()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
