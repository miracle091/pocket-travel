#!/usr/bin/env node
// Simula una guida sull'emulatore Android: legge dal log l'ultimo percorso calcolato dal Navigatore e muove
// il GPS dell'emulatore lungo il percorso, una posizione al secondo.
//
// Il percorso arriva dal log solo nelle build di debug: NavigationViewModel lo scrive con il tag
// PocketTravelRoute ("route <metri> <secondi> lon,lat;lon,lat;..."). Prima di lanciare lo script si apre il
// Navigatore, si sceglie la meta e si tocca "Avvia" (con il GPS dell'emulatore sulla partenza, per esempio
// `adb emu geo fix 24.105 56.95`), cosi' il percorso e' nel log.
//
// Uso: node tools/dev/simulate-route.js [metri al secondo] [secondi massimi] [percorso di adb]
//   metri al secondo  velocita' simulata, default 8 (a piedi ~1.4, in bici ~4.5, in auto in citta' ~12)
//   secondi massimi   quante posizioni mandare al massimo, default tutto il percorso
//   percorso di adb   default "adb" dal PATH
// Per registrare la schermata intanto: adb shell screenrecord --time-limit 170 /sdcard/guida.mp4
const { execFileSync } = require("child_process");

const [speedArg, maxArg, adbArg] = process.argv.slice(2);
const speed = parseFloat(speedArg || "8");
const adb = adbArg || "adb";
if (!(speed > 0)) throw new Error("velocita' non valida: " + speedArg);

const log = execFileSync(adb, ["logcat", "-d", "-s", "PocketTravelRoute:D"], { encoding: "utf8" });
const lines = log.split(/\r?\n/).filter((line) => line.includes(" route "));
if (!lines.length) throw new Error("nessun percorso nel log: in una build di debug apri il Navigatore e tocca Avvia");
const last = lines[lines.length - 1];
const points = last.slice(last.indexOf(" route ") + 7).split(" ")[2].split(";").map((p) => p.split(",").map(Number));

const EARTH_RADIUS_M = 6371000;
const rad = (degrees) => (degrees * Math.PI) / 180;
// Distanza in metri tra due punti [lon, lat], approssimazione piana: basta per segmenti di pochi metri.
const distance = (a, b) => {
  const x = rad(b[0] - a[0]) * Math.cos(rad((a[1] + b[1]) / 2));
  const y = rad(b[1] - a[1]);
  return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
};

// Un punto ogni "speed" metri lungo la polilinea: un secondo di cammino ciascuno.
const samples = [points[0]];
let carry = 0;
for (let i = 1; i < points.length; i++) {
  const a = points[i - 1];
  const b = points[i];
  const d = distance(a, b);
  let t = speed - carry;
  while (t <= d) {
    const f = t / d;
    samples.push([a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]);
    t += speed;
  }
  carry = d - (t - speed);
}
samples.push(points[points.length - 1]);
const steps = Math.min(samples.length, maxArg ? parseInt(maxArg, 10) : samples.length);
console.log(`percorso: ${points.length} punti, ${samples.length} posizioni a ${speed} m/s, ne mando ${steps}`);

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
for (let i = 0; i < steps; i++) {
  const [lon, lat] = samples[i];
  execFileSync(adb, ["emu", "geo", "fix", lon.toFixed(6), lat.toFixed(6)]);
  sleep(1000);
}
console.log(`inviate ${steps} posizioni`);
