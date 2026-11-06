#!/usr/bin/env node
// Simula una guida sull'emulatore Android: legge dal log l'ultimo percorso calcolato dal Navigatore e muove
// il GPS dell'emulatore lungo il percorso, una posizione al secondo.
//
// Il percorso arriva dal log solo nelle build di debug: NavigationViewModel lo scrive con il tag
// PocketTravelRoute ("route <metri> <secondi> <parte>/<parti> lon,lat;lon,lat;...", su piu' righe). Prima di lanciare lo script si apre il
// Navigatore, si sceglie la meta e si tocca "Avvia" (con il GPS dell'emulatore sulla partenza, per esempio
// `adb emu geo fix 24.105 56.95`), cosi' il percorso e' nel log.
//
// Uso: bun (o node) tools/dev/simulate-route.js [metri al secondo] [secondi massimi] [percorso di adb] [opzioni]
//   metri al secondo  velocita' simulata, default 8 (a piedi ~1.4, in bici ~4.5, in auto in citta' ~12)
//   secondi massimi   quante posizioni mandare al massimo, default tutto il percorso
//   percorso di adb   default "adb" dal PATH
//   --rumore=<metri>  errore del GPS: ogni posizione si sposta a caso, con deviazione standard di tanti metri
//                     verso est e verso nord (5 e' un GPS buono all'aperto); senza ricalcoli attesi
//   --deviazione=<secondo>:<metri>  dal secondo indicato la posizione si allontana di lato dal percorso, alla
//                     velocita' simulata, fino a tanti metri, e prosegue parallela: un ricalcolo atteso
// Per registrare la schermata intanto: adb shell screenrecord --time-limit 170 /sdcard/guida.mp4
const { execFileSync } = require("child_process");

const EARTH_RADIUS_M = 6371000;
const rad = (degrees) => (degrees * Math.PI) / 180;
// Distanza in metri tra due punti [lon, lat], approssimazione piana: basta per segmenti di pochi metri.
const distance = (a, b) => {
  const x = rad(b[0] - a[0]) * Math.cos(rad((a[1] + b[1]) / 2));
  const y = rad(b[1] - a[1]);
  return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
};

// Un punto ogni "speed" metri lungo la polilinea [points] ([lon, lat]): un secondo di cammino ciascuno, con la
// direzione del tratto ([est, nord], lunghezza 1) per la deviazione.
function samplesAlong(points, speed) {
  const direction = (a, b) => {
    const x = rad(b[0] - a[0]) * Math.cos(rad(a[1]));
    const y = rad(b[1] - a[1]);
    const length = Math.hypot(x, y) || 1;
    return [x / length, y / length];
  };
  const samples = [{ point: points[0], heading: direction(points[0], points[1] || points[0]) }];
  let carry = 0;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const d = distance(a, b);
    let t = speed - carry;
    while (t <= d) {
      const f = t / d;
      samples.push({ point: [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], heading: direction(a, b) });
      t += speed;
    }
    carry = d - (t - speed);
  }
  samples.push({ point: points[points.length - 1], heading: samples[samples.length - 1].heading });
  return samples;
}

// Normale con media 0 e deviazione standard 1 (Box-Muller), da [random] in [0, 1).
const gaussian = (random) => Math.sqrt(-2 * Math.log(1 - random())) * Math.cos(2 * Math.PI * random());

// Le posizioni da mandare: [samples] spostate di [noise] metri a caso e, dal secondo [deviation.second], di lato
// (a destra della direzione di marcia) fino a [deviation.meters] metri, a [speed] metri al secondo.
function positions(samples, speed, noise, deviation, random = Math.random) {
  return samples.map(({ point, heading }, second) => {
    const side = deviation && second >= deviation.second ? Math.min(deviation.meters, (second - deviation.second + 1) * speed) : 0;
    const east = heading[1] * side + (noise ? gaussian(random) * noise : 0);
    const north = -heading[0] * side + (noise ? gaussian(random) * noise : 0);
    const metersPerDegreeLat = rad(1) * EARTH_RADIUS_M;
    return [point[0] + east / (metersPerDegreeLat * Math.cos(rad(point[1]))), point[1] + north / metersPerDegreeLat];
  });
}

// I punti [lon, lat] dell'ultimo percorso completo nel [log]: logcat tronca le righe oltre circa 4 KB, quindi il percorso
// arriva in piu' righe "route <metri> <secondi> <parte>/<parti> <punti>", da ricomporre.
function lastRoute(log) {
  const parts = log.split(/\r?\n/).filter((line) => line.includes(" route "))
    .map((line) => line.slice(line.indexOf(" route ") + 7).split(" "))
    .map(([, , part, points]) => ({ index: Number(part.split("/")[0]), count: Number(part.split("/")[1]), points }));
  for (let end = parts.length - 1; end >= 0; end--) {
    const { index, count } = parts[end];
    const start = end - count + 1;
    if (index !== count || start < 0) continue;
    const group = parts.slice(start, end + 1);
    if (group.every((p, i) => p.index === i + 1 && p.count === count)) {
      return group.flatMap((p) => p.points.split(";")).map((p) => p.split(",").map(Number));
    }
  }
  throw new Error("nessun percorso completo nel log: in una build di debug apri il Navigatore e tocca Avvia");
}

function main() {
  const args = process.argv.slice(2);
  const options = Object.fromEntries(args.filter((a) => a.startsWith("--")).map((a) => a.slice(2).split("=")));
  const [speedArg, maxArg, adbArg] = args.filter((a) => !a.startsWith("--"));
  const speed = parseFloat(speedArg || "8");
  const adb = adbArg || "adb";
  if (!(speed > 0)) throw new Error("velocita' non valida: " + speedArg);
  const noise = options.rumore === undefined ? 0 : parseFloat(options.rumore);
  if (!(noise >= 0)) throw new Error("rumore non valido: " + options.rumore);
  let deviation = null;
  if (options.deviazione !== undefined) {
    const [second, meters] = options.deviazione.split(":").map(Number);
    if (!(second >= 0 && meters > 0)) throw new Error("deviazione non valida, si scrive <secondo>:<metri>: " + options.deviazione);
    deviation = { second, meters };
  }

  const log = execFileSync(adb, ["logcat", "-d", "-s", "PocketTravelRoute:D"], { encoding: "utf8" });
  const points = lastRoute(log);

  const sent = positions(samplesAlong(points, speed), speed, noise, deviation);
  const steps = Math.min(sent.length, maxArg ? parseInt(maxArg, 10) : sent.length);
  console.log(`percorso: ${points.length} punti, ${sent.length} posizioni a ${speed} m/s, ne mando ${steps}`);

  const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  for (let i = 0; i < steps; i++) {
    const [lon, lat] = sent[i];
    execFileSync(adb, ["emu", "geo", "fix", lon.toFixed(6), lat.toFixed(6)]);
    sleep(1000);
  }
  console.log(`inviate ${steps} posizioni`);
}

if (require.main === module) main();

module.exports = { samplesAlong, positions, lastRoute };
