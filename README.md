# OBD Monitor Android

App Android nativa in Kotlin + Jetpack Compose per adattatori ELM327 Bluetooth Classic.

## Funzioni incluse
- scelta tra dispositivi Bluetooth gia associati;
- connessione RFCOMM/SPP a ELM327;
- rilevamento automatico protocollo (`ATSP0`);
- lettura ciclica di PID OBD-II standard: RPM, velocita, temperature, carico, MAF, MAP, acceleratore, carburante, tensione e fuel trim;
- esclusione automatica dei PID non supportati dalla centralina.

## Ottenere l'APK con GitHub
1. Crea un repository GitHub vuoto.
2. Carica tutto il contenuto di questa cartella nella root del repository.
3. Apri **Actions > Build APK > Run workflow**.
4. Terminata la build, scarica l'artifact **OBDMonitor-debug-apk**.
5. Estrai lo ZIP e installa `app-debug.apk` sul telefono, autorizzando l'installazione da fonte esterna.

## Uso
1. Inserisci l'adattatore OBD a motore spento.
2. Accendi il quadro.
3. Associa l'ELM327 nelle impostazioni Bluetooth Android (PIN frequenti: 1234 o 0000).
4. Apri l'app, concedi l'accesso ai dispositivi nelle vicinanze e premi **Connetti**.

## Limiti importanti
- Questa versione supporta Bluetooth Classic SPP. Gli adattatori BLE e Wi-Fi richiedono trasporti diversi.
- I dati standard disponibili dipendono da auto, ECU e adattatore.
- DPF, AdBlue, cambio automatico, batteria ibrida/EV e altri dati avanzati usano spesso PID proprietari Mode 22 e richiedono marca, modello, anno e motore.
- Non usare l'interfaccia durante la guida. Non lasciare adattatori economici permanentemente inseriti.
