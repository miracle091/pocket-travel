package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggiunta di Pocket Travel, non presente in BRouter upstream (tag v1.7.10): accesso in sola
 * lettura alle indicazioni di svolta di un percorso calcolato. {@link OsmTrack#voiceHints} e'
 * pubblico, ma la sua lista e i campi di {@link VoiceHint} (comando, distanza, indice nel
 * tracciato) sono package-private: questa classe vive nello stesso package per leggerli senza
 * reflection e senza toccare le classi originali. Le costanti replicano quelle di VoiceHint.
 */
public final class TurnInstructions {
  public static final int CONTINUE = VoiceHint.C;
  public static final int TURN_LEFT = VoiceHint.TL;
  public static final int TURN_SLIGHTLY_LEFT = VoiceHint.TSLL;
  public static final int TURN_SHARPLY_LEFT = VoiceHint.TSHL;
  public static final int TURN_RIGHT = VoiceHint.TR;
  public static final int TURN_SLIGHTLY_RIGHT = VoiceHint.TSLR;
  public static final int TURN_SHARPLY_RIGHT = VoiceHint.TSHR;
  public static final int KEEP_LEFT = VoiceHint.KL;
  public static final int KEEP_RIGHT = VoiceHint.KR;
  public static final int U_TURN_LEFT = VoiceHint.TLU;
  public static final int U_TURN_RIGHT = VoiceHint.TRU;
  public static final int OFF_ROUTE = VoiceHint.OFFR;
  public static final int ROUNDABOUT = VoiceHint.RNDB;
  public static final int ROUNDABOUT_LEFT = VoiceHint.RNLB;
  public static final int U_TURN = VoiceHint.TU;
  public static final int BEELINE = VoiceHint.BL;
  public static final int EXIT_LEFT = VoiceHint.EL;
  public static final int EXIT_RIGHT = VoiceHint.ER;
  public static final int END = VoiceHint.END;

  /** Una svolta: comando (costanti sopra), uscita della rotonda (0 se non e' una rotonda),
   *  metri fino all'indicazione successiva, indice del nodo in {@link OsmTrack#nodes}. */
  public static final class Turn {
    public final int command;
    public final int roundaboutExit;
    public final double distanceToNext;
    public final int indexInTrack;

    Turn(int command, int roundaboutExit, double distanceToNext, int indexInTrack) {
      this.command = command;
      this.roundaboutExit = roundaboutExit;
      this.distanceToNext = distanceToNext;
      this.indexInTrack = indexInTrack;
    }
  }

  private TurnInstructions() {
  }

  /** Le svolte del tracciato nell'ordine in cui compaiono; vuota se il profilo non le calcola
   *  (turnInstructionMode = 0). */
  public static List<Turn> of(OsmTrack track) {
    if (track == null || track.voiceHints == null) return Collections.emptyList();
    List<Turn> turns = new ArrayList<>(track.voiceHints.list.size());
    for (VoiceHint hint : track.voiceHints.list) {
      turns.add(new Turn(hint.cmd, hint.getExitNumber(), hint.distanceToNext, hint.indexInTrack));
    }
    turns.sort((a, b) -> Integer.compare(a.indexInTrack, b.indexInTrack));
    return turns;
  }
}
