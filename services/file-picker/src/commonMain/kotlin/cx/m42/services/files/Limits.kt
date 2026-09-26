package cx.m42.services.files

/**
 * The largest file a picker reads. What people pick here is a key (a few kilobytes) or a backup
 * (tens of kilobytes); a video picked by mistake must not be read whole into memory to find out.
 */
public const val MAX_PICKED_BYTES: Int = 4 * 1024 * 1024
