package com.navlistener;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model data navigasi dari Google Maps.
 * Termasuk field nextManeuver untuk preview arah berikutnya.
 */
public class NavState {

    public boolean active       = false;
    public String  maneuver     = "straight";
    public float   distance     = 0;
    public String  distUnit     = "m";
    public String  street       = "---";
    public String  eta          = "--:--";
    public float   remainDist   = 0;
    public String  remainUnit   = "km";
    public int     speed        = 0;
    public int     heading      = 0;
    public String  lat          = "0.000000";
    public String  lng          = "0.000000";

    // Arah berikutnya (after next turn)
    public String  nextManeuver = "straight";
    public String  nextStreet   = "";
    public float   nextDistance = 0;

    public long    lastUpdate   = 0;

    public NavState() {
        lastUpdate = System.currentTimeMillis();
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("a",  active ? 1 : 0);
            j.put("m",  maneuver);
            j.put("d",  distance);
            j.put("u",  distUnit);
            j.put("s",  street.length() > 40 ? street.substring(0, 40) : street);
            j.put("e",  eta);
            j.put("r",  remainDist);
            j.put("ru", remainUnit);
            j.put("sp", speed);
            j.put("h",  heading);
            j.put("la", lat);
            j.put("ln", lng);
            j.put("nm", nextManeuver);
            j.put("ns", nextStreet.length() > 30 ? nextStreet.substring(0, 30) : nextStreet);
            j.put("nd", nextDistance);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return j;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    public static String maneuverToLabel(String m) {
        if (m == null) return "Lurus";
        switch (m) {
            case "straight":           return "Lurus";
            case "turn-right":         return "Belok Kanan";
            case "turn-left":          return "Belok Kiri";
            case "turn-slight-right":  return "Agak Kanan";
            case "turn-slight-left":   return "Agak Kiri";
            case "turn-sharp-right":   return "Tajam Kanan";
            case "turn-sharp-left":    return "Tajam Kiri";
            case "uturn-left":
            case "uturn-right":        return "Balik Arah";
            case "roundabout-left":
            case "roundabout-right":   return "Bundaran";
            case "destination":        return "Tiba di Tujuan";
            case "merge":              return "Gabung Jalur";
            case "ramp-right":
            case "ramp-left":          return "Keluar/Ramp";
            case "fork-right":         return "Ambil Kanan";
            case "fork-left":          return "Ambil Kiri";
            default:                   return m;
        }
    }

    public String maneuverLabel()     { return maneuverToLabel(maneuver); }
    public String nextManeuverLabel() { return maneuverToLabel(nextManeuver); }

    public String distanceFormatted() {
        return "km".equals(distUnit)
            ? String.format("%.1f km", distance)
            : String.format("%.0f m", distance);
    }
}
