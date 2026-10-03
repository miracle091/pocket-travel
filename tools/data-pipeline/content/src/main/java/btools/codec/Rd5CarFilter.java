package btools.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.OsmFile;
import btools.mapaccess.PhysicalFile;
import btools.util.Crc32;

/**
 * Variante "solo auto" di un segmento BRouter .rd5 (Pocket Travel, pipeline dei dati): ogni micro-cella si decodifica
 * con un validatore che scarta le vie che un'auto non puo' usare, si ricodifica con MicroCache2.encodeMicroCache e il
 * file si riscrive con indici e CRC come l'originale. Nel package btools.codec perche' usa campi e metodi di
 * MicroCache visibili solo qui. Senza filtro (carOnly = false) la riscrittura e' identica byte per byte all'originale.
 *
 * Misura del 2026-10-02 su E10_N45.rd5 (199 MB): variante auto 86 MB (-57%), stessi percorsi in auto (car-vario) del
 * file completo. Il filtro tiene tutte le chiavi dei tag (isLookupIdxUsed sempre true): servono ai profili e alle svolte.
 */
public final class Rd5CarFilter {

  private Rd5CarFilter() {}

  /** Riscrive [in] in [out] con le sole vie per l'auto (carOnly) o tutte; [lookups] e' il lookups.dat dei profili. */
  public static void rewrite(File lookups, File in, File out, boolean carOnly) throws IOException {
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay ctx = new BExpressionContextWay(meta);
    meta.readMetaData(lookups);
    process(in, carOnly ? new CarValidator(ctx) : null, out);
  }

  /**
   * true se un'auto puo' usare la via con questi tag. Permissiva: tiene traghetti, strade di servizio, accessi
   * "destination"/privati e le track di grado 1, che car-vario usa; il mezzo si legge da motorcar, poi motor_vehicle,
   * vehicle e infine access.
   */
  public static boolean carAccepts(Map<String, String> tags) {
    String hw = tags.getOrDefault("highway", "");
    String access = tags.getOrDefault("access", "");
    String motor = first(tags, "motorcar", "motor_vehicle", "vehicle", "access");
    boolean motorNo = motor.equals("no");
    boolean motorYes = motor.equals("yes") || motor.equals("designated") || motor.equals("permissive") || motor.equals("destination");
    if (tags.getOrDefault("route", "").equals("ferry")) return !motorNo;
    switch (hw) {
      case "motorway": case "motorway_link": case "trunk": case "trunk_link":
      case "primary": case "primary_link": case "secondary": case "secondary_link":
      case "tertiary": case "tertiary_link": case "unclassified": case "residential":
      case "living_street": case "service": case "road": case "rest_area": case "services":
        if (motorNo) return false;
        return !access.equals("no") || motorYes;
      case "track":
        return motorYes || (!motorNo && tags.getOrDefault("tracktype", "").equals("grade1"));
      default: // footway, path, steps, pedestrian, cycleway, bridleway, construction, platform...
        return motorYes;
    }
  }

  private static String first(Map<String, String> tags, String... keys) {
    for (String key : keys) {
      String value = tags.get(key);
      if (value != null && !value.isEmpty()) return value;
    }
    return "";
  }

  private static final class CarValidator implements TagValueValidator {
    private final BExpressionContextWay ctx;

    CarValidator(BExpressionContextWay ctx) { this.ctx = ctx; }

    @Override
    public int accessType(byte[] tvs) {
      List<String> kv = ctx.getKeyValueList(false, tvs);
      Map<String, String> tags = new HashMap<>();
      for (int i = 0; i + 1 < kv.size(); i += 2) tags.put(kv.get(i), kv.get(i + 1));
      return carAccepts(tags) ? 2 : 0;
    }

    @Override
    public byte[] unify(byte[] ab, int offset, int len) {
      byte[] r = new byte[len];
      System.arraycopy(ab, offset, r, 0, len);
      return r;
    }

    @Override
    public boolean isLookupIdxUsed(int idx) { return true; }

    @Override
    public void setDecodeForbidden(boolean decodeForbidden) {}

    @Override
    public boolean checkStartWay(byte[] ab) { return true; }
  }

  // Legge ogni micro-cella con PhysicalFile/OsmFile (lo stesso percorso di lettura dell'app) e la riscrive in out:
  // 25 tile da 1 grado, ognuna con la tabella delle posizioni delle micro-celle e le celle col loro CRC (^2), poi il
  // piede del file (data di creazione, CRC dell'indice, CRC delle 25 tabelle e la coda originale).
  private static void process(File in, TagValueValidator validator, File out) throws IOException {
    DataBuffers db = new DataBuffers();
    PhysicalFile pf = new PhysicalFile(in, db, -1, -1);
    try (RandomAccessFile raw = new RandomAccessFile(in, "r")) {
      int div = pf.divisor;
      byte[] encBuf = new byte[32 << 20];
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      long[] ends = new long[25];
      int[] headerCrcs = new int[25];
      long[] versions = new long[25];
      for (int i = 0; i < 25; i++) versions[i] = raw.readLong() >>> 48;
      for (int lonDeg = 0; lonDeg < 5; lonDeg++) {
        for (int latDeg = 0; latDeg < 5; latDeg++) {
          int tileIndex = lonDeg * 5 + latDeg;
          OsmFile osmf = new OsmFile(pf, lonDeg, latDeg, db);
          if (osmf.hasData()) {
            int[] pos = new int[div * div];
            ByteArrayOutputStream cellsOut = new ByteArrayOutputStream();
            boolean any = false;
            for (int sub = 0; sub < div * div; sub++) {
              int lonIdx = lonDeg * div + sub % div;
              int latIdx = latDeg * div + sub / div;
              MicroCache mc = osmf.createMicroCache(lonIdx, latIdx, db, validator, null, true, null);
              if (mc.getSize() > 0) {
                int len = mc.encodeMicroCache(encBuf);
                cellsOut.write(encBuf, 0, len);
                int crc = Crc32.crc(encBuf, 0, len) ^ 2;
                cellsOut.write(new byte[]{(byte) (crc >> 24), (byte) (crc >> 16), (byte) (crc >> 8), (byte) crc});
                any = true;
              }
              pos[sub] = div * div * 4 + cellsOut.size();
            }
            if (any) {
              ByteArrayOutputStream idx = new ByteArrayOutputStream();
              DataOutputStream d = new DataOutputStream(idx);
              for (int p : pos) d.writeInt(p);
              byte[] ib = idx.toByteArray();
              headerCrcs[tileIndex] = Crc32.crc(ib, 0, ib.length);
              body.write(ib);
              cellsOut.writeTo(body);
            }
          }
          ends[tileIndex] = 200 + body.size();
        }
      }
      raw.seek(24 * 8);
      long origEnd = raw.readLong() & 0xffffffffffffL;
      raw.seek(origEnd);
      long creationTime = raw.readLong();
      byte[] tail = new byte[(int) (raw.length() - origEnd - 8 - 4 - 100)];
      raw.seek(origEnd + 112);
      raw.readFully(tail);
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      DataOutputStream hd = new DataOutputStream(head);
      for (int i = 0; i < 25; i++) hd.writeLong(versions[i] << 48 | ends[i]);
      byte[] hb = head.toByteArray();
      int crc = Crc32.crc(hb, 0, 200) ^ (div == 32 ? 2 : 0);
      try (DataOutputStream o = new DataOutputStream(new FileOutputStream(out))) {
        o.write(hb);
        body.writeTo(o);
        o.writeLong(creationTime);
        o.writeInt(crc);
        for (int c : headerCrcs) o.writeInt(c);
        o.write(tail);
      }
    } finally {
      pf.close();
    }
  }
}
