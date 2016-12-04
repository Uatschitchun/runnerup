/*
 * Copyright (C) 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.export.format;

import android.annotation.TargetApi;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import org.runnerup.common.util.Constants.DB;
import org.runnerup.util.KXmlSerializer;
import org.runnerup.workout.Sport;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

@TargetApi(Build.VERSION_CODES.FROYO)
public class GPX {

    final boolean export_rest_laps = false;

    enum RestLapMode {
        EMPTY_TRKSEG,
        START_STOP_TRKSEG
    }

    final RestLapMode restLapMode = RestLapMode.START_STOP_TRKSEG;

    long mID = 0;
    SQLiteDatabase mDB = null;
    KXmlSerializer mXML = null;
    String notes = null;
    SimpleDateFormat simpleDateFormat = null;
    private final boolean mPrivateExtensions;

    public GPX(SQLiteDatabase mDB) {
        this(mDB, false);
    }

    public GPX(SQLiteDatabase mDB, boolean privateExtensions) {
        this.mDB = mDB;
        simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.mPrivateExtensions = privateExtensions;
    }

    String formatTime(long time) {
        return simpleDateFormat.format(new Date(time));
    }

    /**
     * @param activityId
     * @param writer
     * @throws IOException
     */
    public String export(long activityId, Writer writer) throws IOException {

        String[] aColumns = {
                DB.ACTIVITY.NAME, DB.ACTIVITY.COMMENT,
                DB.ACTIVITY.START_TIME, DB.ACTIVITY.SPORT, DB.ACTIVITY.META_DATA
        };
        Cursor cursor = mDB.query(DB.ACTIVITY.TABLE, aColumns, "_id = "
                + activityId, null, null, null, null);
        cursor.moveToFirst();

        long startTime = cursor.getLong(2); // epoch
        try {
            mXML = new KXmlSerializer();
            mXML.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            mXML.setOutput(writer);
            mXML.startDocument("UTF-8", true);
            mXML.startTag("", "gpx");
            mXML.attribute("", "version", "1.1");
            String creator = "RunnerUp " + android.os.Build.MODEL;
            if (!cursor.isNull(4)) {
                String metaData = cursor.getString(4);
                if (metaData.contains(DB.ACTIVITY.WITH_BAROMETER)) {
                    creator += " with barometer";
                }
            }
            mXML.attribute("", "creator", creator);
            mXML.attribute("", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            mXML.attribute("", "xmlns", "http://www.topografix.com/GPX/1/1");
            mXML.attribute("", "xsi:schemaLocation",
                    "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd");
            mXML.attribute("", "xmlns:gpxtpx",
                    "http://www.garmin.com/xmlschemas/TrackPointExtension/v1");

            mXML.startTag("", "metadata");
            mXML.startTag("", "time");
            final String time = formatTime(startTime * 1000);
            mXML.text(time);
            mXML.endTag("", "time");
            mXML.endTag("", "metadata");

            mXML.startTag("", "trk");
            mXML.startTag("", "name");
            String sportName;
            if (cursor.isNull(3)) {
                sportName = "Running";
            } else {
                //Resources are not available, use hardcoded strings
                sportName = Sport.textOf(cursor.getInt(3));
            }

            mXML.text("RunnerUp-"+sportName+"-"+time);
            mXML.endTag("", "name");
            if (!cursor.isNull(1)) {
                notes = cursor.getString(1);
                mXML.startTag("", "desc");
                mXML.text(notes);
                mXML.endTag("", "desc");
            }

            exportLaps(activityId, startTime * 1000);
            mXML.endTag("", "trk");
            mXML.endTag("", "gpx");
            mXML.flush();
            mXML.endDocument();
            mXML = null;
            cursor.close();
            return time;
        } catch (IOException e) {
            cursor.close();
            mXML = null;
            throw e;
        }
    }

    private void exportLaps(long activityId, long startTime) throws IOException {
        String[] lColumns = {
                DB.LAP.LAP, DB.LAP.DISTANCE, DB.LAP.TIME,
                DB.LAP.INTENSITY
        };

        Cursor cLap = mDB.query(DB.LAP.TABLE, lColumns, "( " + DB.LAP.DISTANCE + " > 0 or "
                + DB.LAP.TIME + " > 0) and "
                + DB.LAP.ACTIVITY + " = " + activityId, null, null, null, null);
        String[] pColumns = {
                DB.LOCATION.LAP, DB.LOCATION.TIME,
                DB.LOCATION.LATITUDE, DB.LOCATION.LONGITUDE,
                DB.LOCATION.ALTITUDE, DB.LOCATION.TYPE,
                DB.LOCATION.HR, DB.LOCATION.CADENCE, DB.LOCATION.TEMPERATURE, DB.LOCATION.PRESSURE,
                DB.LOCATION.ACCURANCY, DB.LOCATION.BEARING, DB.LOCATION.SPEED,
                DB.LOCATION.SATELLITES, DB.LOCATION.GPS_ALTITUDE
        };
        Cursor cLocation = mDB.query(DB.LOCATION.TABLE, pColumns,
                DB.LOCATION.ACTIVITY + " = " + activityId, null, null, null,
                null);
        boolean lok = cLap.moveToFirst();
        boolean pok = cLocation.moveToFirst();

        while (lok) {
            if (cLap.getFloat(1) != 0 && cLap.getLong(2) != 0) {
                long lap = cLap.getLong(0);
                while (pok && cLocation.getLong(0) != lap) {
                    pok = cLocation.moveToNext();
                }
                mXML.startTag("", "trkseg");
                if (pok && cLocation.getLong(0) == lap) {
                    float last_lat = 0;
                    float last_longi = 0;
                    long last_time = 0;
                    while (pok && cLocation.getLong(0) == lap) {
                        long time = cLocation.getLong(1);
                        float lat = cLocation.getFloat(2);
                        float longi = cLocation.getFloat(3);
                        if (!(time == last_time && lat == last_lat && longi != last_longi)) {
                            mXML.startTag("", "trkpt");
                            mXML.attribute("", "lon", Float.toString(longi));
                            mXML.attribute("", "lat", Float.toString(lat));
                            Float ele = null;
                            if (mPrivateExtensions && !cLocation.isNull(14)) {
                                ele = cLocation.getFloat(14);
                            }
                            else if (mPrivateExtensions && !cLocation.isNull(14)) {
                                ele = cLocation.getFloat(4);
                            }
                            if (ele != null) {
                                mXML.startTag("", "ele");
                                mXML.text("" + ele);
                                mXML.endTag("", "ele");
                            }
                            mXML.startTag("", "time");
                            mXML.text(formatTime(time));
                            mXML.endTag("", "time");

                            {
                                //Garmin's GPX extensions for non standard data (other variants exists too, like Cluetrust)
                                //Check app specific like Strava: https://strava.github.io/api/v3/uploads/
                                //Private extensions are normally not used externally
                                boolean isHr = !cLocation.isNull(6);
                                boolean isCad = !cLocation.isNull(7);
                                boolean isTemp = !cLocation.isNull(8);
                                boolean isPres = !cLocation.isNull(9) && mPrivateExtensions;
                                boolean isAccuracy = !cLocation.isNull(10) && mPrivateExtensions;
                                boolean isBearing = !cLocation.isNull(11) && mPrivateExtensions;
                                boolean isSpeed = !cLocation.isNull(12) && mPrivateExtensions;
                                boolean isSats = !cLocation.isNull(13) && mPrivateExtensions;
                                boolean isAny = isCad || isTemp || isPres || isAccuracy || isBearing || isSpeed || isHr || isSats;
                                if (isAny) {
                                    mXML.startTag("", "extensions");
                                    mXML.startTag("", "gpxtpx:TrackPointExtension");
                                }

                                if (isHr) {
                                    mXML.startTag("", "gpxtpx:hr");
                                    String bpm = Integer.toString(cLocation.getInt(6));
                                    mXML.text(bpm);
                                    mXML.endTag("", "gpxtpx:hr");
                                }
                                if (isCad) {
                                    //Not supported by Strava?
                                    mXML.startTag("", "gpxtpx:cad");
                                    String val = Float.toString(cLocation.getFloat(7));
                                    mXML.text(val);
                                    mXML.endTag("", "gpxtpx:cad");
                                }
                                if (isTemp) {
                                    mXML.startTag("", "gpxtpx:atemp");
                                    String val = Float.toString(cLocation.getFloat(8));
                                    mXML.text(val);
                                    mXML.endTag("", "gpxtpx:atemp");
                                }
                                if (isPres) {
                                    mXML.startTag("", "pressure");
                                    String val = Float.toString(cLocation.getFloat(9));
                                    mXML.text(val);
                                    mXML.endTag("", "pressure");
                                }
                                if (isAccuracy) {
                                    mXML.startTag("", "accuracy");
                                    String val = Float.toString(cLocation.getFloat(10));
                                    mXML.text(val);
                                    mXML.endTag("", "accuracy");
                                }
                                if (isBearing) {
                                    mXML.startTag("", "bearing");
                                    String val = Float.toString(cLocation.getFloat(11));
                                    mXML.text(val);
                                    mXML.endTag("", "bearing");
                                }
                                if (isSpeed) {
                                    mXML.startTag("", "speed");
                                    String val = Float.toString(cLocation.getFloat(12));
                                    mXML.text(val);
                                    mXML.endTag("", "speed");
                                }
                                if (isSats) {
                                    mXML.startTag("", "sat");
                                    String val = Float.toString(cLocation.getFloat(13));
                                    mXML.text(val);
                                    mXML.endTag("", "sat");
                                }

                                if (isAny) {
                                    mXML.endTag("", "gpxtpx:TrackPointExtension");
                                    mXML.endTag("", "extensions");
                                }
                            }

                            mXML.endTag("", "trkpt");
                            last_time = time;
                            last_lat = lat;
                            last_longi = longi;
                        }
                        pok = cLocation.moveToNext();
                    }
                }
                mXML.endTag("", "trkseg");
            } else if (export_rest_laps && (cLap.getFloat(1) != 0 || cLap.getLong(2) != 0)) {
                long lap = cLap.getLong(0);
                if (restLapMode == RestLapMode.START_STOP_TRKSEG) {
                    if (lap > 0 && !cLap.isLast()) {
                        Cursor cStart = mDB.query(DB.LOCATION.TABLE, pColumns,
                                DB.LOCATION.ACTIVITY + " = " + activityId + " and "
                                        + DB.LOCATION.LAP + " = " + (lap - 1), null, null, null,
                                "_id desc", "1");
                        Cursor cEnd = mDB.query(DB.LOCATION.TABLE, pColumns,
                                DB.LOCATION.ACTIVITY + " = " + activityId + " and "
                                        + DB.LOCATION.LAP + " = " + (lap + 1), null, null, null,
                                "_id asc", "1");

                        if (cStart.moveToFirst() && cEnd.moveToFirst()) {
                            mXML.startTag("", "trkseg");

                            long time_0 = cStart.getLong(1);
                            float lat_0 = cStart.getFloat(2);
                            float longi_0 = cStart.getFloat(3);

                            long time_1 = cEnd.getLong(1);
                            float lat_1 = cEnd.getFloat(2);
                            float longi_1 = cEnd.getFloat(3);

                            mXML.startTag("", "trkpt");
                            mXML.attribute("", "lon", Float.toString(longi_0));
                            mXML.attribute("", "lat", Float.toString(lat_0));
                            if (!cStart.isNull(4)) {
                                mXML.startTag("", "ele");
                                mXML.text("" + cStart.getLong(4));
                                mXML.endTag("", "ele");
                            }
                            mXML.startTag("", "time");
                            mXML.text(formatTime(time_0));
                            mXML.endTag("", "time");
                            mXML.endTag("", "trkpt");

                            mXML.startTag("", "trkpt");
                            mXML.attribute("", "lon", Float.toString(longi_1));
                            mXML.attribute("", "lat", Float.toString(lat_1));
                            if (!cEnd.isNull(4)) {
                                mXML.startTag("", "ele");
                                mXML.text("" + cEnd.getLong(4));
                                mXML.endTag("", "ele");
                            }
                            mXML.startTag("", "time");
                            mXML.text(formatTime(time_1));
                            mXML.endTag("", "time");
                            mXML.endTag("", "trkpt");

                            mXML.endTag("", "trkseg");
                        }

                        cStart.close();
                        cEnd.close();
                    }
                } else if (restLapMode == RestLapMode.EMPTY_TRKSEG) {
                    mXML.startTag("", "trkseg");
                    mXML.endTag("", "trkseg");
                }
            }

            lok = cLap.moveToNext();
        }
        cLap.close();
        cLocation.close();
    }

    public String getNotes() {
        return notes;
    }
}
