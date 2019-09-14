package co.nayuta.lightning;

public class ShortChannelParam {
    int height;
    int bIndex;
    int vIndex;
    byte[] minedHash;       //JNI戻り値専用
    //
    //
    ShortChannelParam() {
        this(-1, -1, -1);
    }
    ShortChannelParam(int height, int bIndex, int vIndex) {
        this.height = height;
        this.bIndex = bIndex;
        this.vIndex = vIndex;
    }
    ShortChannelParam(long shortChannelId) {
        initialize(shortChannelId);
    }
    //
    void initialize(long shortChannelId) {
        if (shortChannelId != 0) {
            this.height = (int)(shortChannelId >>> 40);
            this.bIndex = (int)((shortChannelId & 0xffffff0000L) >>> 16);
            this.vIndex = (int)(shortChannelId & 0xffff);
        }
    }
    //
    boolean isAvailable() {
        return (this.height > 0) && (this.bIndex > 0) && (this.vIndex >= 0);
    }
    //
    @Override
    public String toString() {
        return String.format("height:%d, bIndex:%d, vIndex:%d", height, bIndex, vIndex);
    }
}
