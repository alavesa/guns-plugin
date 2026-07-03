# Guns — käyttöönotto ja omien 3D-mallien teko (suomeksi)

Tämä ohje vie alusta loppuun: plugin serverille → resource pack käyttöön → omat mallit.

## 1. Pluginin asennus (kerran)

1. Lataa uusin `Guns-x.y.z.jar` releaseista:
   https://github.com/alavesa/guns-plugin/releases
2. Kopioi jar serverin `plugins/`-kansioon.
3. Käynnistä serveri uudelleen.
4. Testaa pelissä: `/guns list` → pitäisi näkyä `pistol, rifle, venom | frag`.

## 2. Resource packin käyttöönotto

Resource pack sisältää valmiit placeholder-3D-mallit kaikille aseille — ne toimivat heti,
ja voit korvata ne omilla malleilla myöhemmin.

**Tapa A — vain itselle (helpoin aloitus):**
1. Lataa `GunsResourcePack.zip` releasesta.
2. Siirrä zip **purkamatta** Minecraftin `resourcepacks`-kansioon
   (pelissä: Asetukset → Resurssipaketit → Avaa pakettikansio).
3. Ota paketti käyttöön pelin resurssipakettivalikosta.
4. `/guns give pistol` → kädessä pitäisi näkyä 3D-pistooli (ei varsijousi).

**Tapa B — koko serverille (kaikki näkevät mallit automaattisesti):**
1. Laita `GunsResourcePack.zip` jonnekin, mistä sen voi ladata suoralla linkillä
   (esim. GitHub-releasen liite — sen "Download"-linkki käy sellaisenaan).
2. Avaa serverin `server.properties` ja aseta:
   ```
   resource-pack=https://... (suora linkki zippiin)
   require-resource-pack=true
   ```
3. Käynnistä serveri uudelleen. Pelaajille tarjotaan paketti liittyessä.
4. HUOM: kun päivität pakettia, pelaajan pitää hyväksyä se uudelleen — jos vanha jää
   välimuistiin, aseta myös `resource-pack-sha1` tai vaihda tiedoston nimeä.

## 3. Miten mallit on kytketty (tärkeä ymmärtää!)

Jokaisella aseella on `model`-statti (esim. `gun_pistol`). Ketju toimii näin:

```
/guns edit <ase> model gun_pistol          (pluginin puoli - itemiin tallentuu merkkijono)
        ↓
assets/minecraft/items/crossbow.json       (valitsee mallin merkkijonon perusteella)
        ↓
assets/guns/models/item/gun_pistol.json    (itse 3D-malli)
```

Aseet ovat varsijousia → niiden valinnat ovat `items/crossbow.json`-tiedostossa.
Kranaatit ovat lumipalloja → `items/snowball.json`.

## 4. Oman 3D-mallin teko Blockbenchillä

1. Lataa **Blockbench**: https://www.blockbench.net (ilmainen).
2. Uusi projekti → tyyppi **Java Block/Item**.
3. Rakenna ase kuutioista (Add Cube). Vinkkejä:
   - Piipun suunta: placeholder-malleissa piippu osoittaa **−Z-suuntaan** (pohjoiseen).
   - Koko: noin 8–20 "pikseliä" pitkä ase istuu käteen hyvin.
   - Tekstuurit: voit maalata omat TAI käyttää vanillan tekstuureja kuten placeholderit
     (esim. `minecraft:block/iron_block`).
4. Katso **Display-välilehti**: siellä säädetään miltä ase näyttää kädessä (1. ja 3. persoona),
   maassa ja inventaariossa. Kopioi lähtökohdaksi placeholder-mallin `display`-osio ja säädä.
5. Vie malli: **File → Export → Export Block/Item Model** → tallenna nimellä esim.
   `gun_sniper.json`.

## 5. Mallin lisääminen pakettiin

1. Kopioi `gun_sniper.json` kansioon `assets/guns/models/item/`.
   (Jos maalasit omat tekstuurit, ne menevät `assets/guns/textures/item/`-kansioon ja mallin
   `textures`-osiossa niihin viitataan `guns:item/tekstuurin_nimi`.)
2. Lisää valintarivi tiedostoon `assets/minecraft/items/crossbow.json` cases-listaan:
   ```json
   { "when": "gun_sniper", "model": { "type": "minecraft:model", "model": "guns:item/gun_sniper" } }
   ```
   (Kranaateille sama juttu `items/snowball.json`-tiedostoon.)
3. Kerro pluginille: `/guns edit sniper model gun_sniper` ja `/guns give sniper`.
4. Pakkaa paketti uudelleen zipiksi niin, että `pack.mcmeta` on zipin JUURESSA
   (eli zippaa kansion SISÄLTÖ, älä kansiota itseään) — ja ota käyttöön.
5. Pelissä tekstuurit voi ladata uudelleen näppäimillä **F3+T** (jos paketti on omassa
   resourcepacks-kansiossa; serverin jakamana pitää liittyä uudelleen).

## 6. Jos malli ei näy — tarkistuslista

- Näkyykö ase varsijousena? → merkkijono ei täsmää: `model`-statin, `crossbow.json`-casen
  ja mallitiedoston nimen pitää olla TÄSMÄLLEEN samat (isot/pienet kirjaimet!).
- Näkyykö musta-violetti ruutu? → malli löytyi mutta tekstuuri ei: tarkista mallin
  `textures`-polut.
- Ei mitään muutosta? → paketti ei ole käytössä tai zipin juuressa ei ole `pack.mcmeta`a.
- Vanha malli näkyy? → F3+T, tai serveripaketin tapauksessa vaihda zipin nimi/sha1.
