package dev.local.veil.sharing;
import android.os.ParcelFileDescriptor;
interface ITetheringService {
    ParcelFileDescriptor prepare(IBinder lease) = 0;
    void activate() = 1;
    String status() = 2;
    void stop() = 3;
    void destroy() = 16777114;
}
