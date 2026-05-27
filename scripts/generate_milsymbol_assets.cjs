#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const ms = require("milsymbol");

const root = path.resolve(__dirname, "..");
const outputDir = path.join(root, "android/app/src/main/assets/milsymbol");
const size = 67.5;

const affiliations = {
  FRIEND: "F",
  HOSTILE: "H",
  NEUTRAL: "N",
  UNKNOWN: "U"
};

const echelons = {
  TEAM: "A",
  SECTION: "C",
  PLATOON: "D",
  COMPANY: "E",
  BATTALION_REGIMENT: "F",
  BRIGADE: "H",
  DIVISION: "I"
};

const functions = {
  AIR_DEFENCE: { dimension: "G", id: "UCD---" },
  AMMUNITION: { dimension: "G", id: "USXO--" },
  ANTI_TANK: { dimension: "G", id: "UCAA--" },
  ARMOUR: { dimension: "G", id: "UCA---" },
  ARTILLERY: { dimension: "G", id: "UCF---" },
  AVIATION_FIXED: { dimension: "G", id: "UCVF--" },
  AVIATION: { dimension: "G", id: "UCVR--" },
  BRIDGING: { dimension: "G", id: "UCE---" },
  CAVALRY: { dimension: "G", id: "UCRV--" },
  CBRN: { dimension: "G", id: "UUA---" },
  CSS: { dimension: "G", id: "US----" },
  ELECTRONIC_WARFARE: { dimension: "G", id: "UUMSE-" },
  ENGINEER: { dimension: "G", id: "UCE---" },
  EOD: { dimension: "G", id: "UUE---" },
  INFANTRY: { dimension: "G", id: "UCI---" },
  MAINTENANCE: { dimension: "G", id: "USX---" },
  MECH_INFANTRY: { dimension: "G", id: "UCIZ--" },
  MEDICAL: { dimension: "G", id: "USM---" },
  MILITARY_POLICE: { dimension: "G", id: "UULM--" },
  MORTAR: { dimension: "G", id: "UCFM--" },
  MOTORISED_INFANTRY: { dimension: "G", id: "UCIM--" },
  RADAR: { dimension: "G", id: "UUMRG-" },
  RECCE: { dimension: "G", id: "UCR---" },
  SIGNAL: { dimension: "G", id: "UUS---" },
  SPECIAL_FORCES: { dimension: "F", id: "GS----" },
  LOGISTICS: { dimension: "G", id: "USS---" },
  TRANSPORTATION: { dimension: "G", id: "UST---" },
  UAV: { dimension: "G", id: "UCVUF-" },
  UNSPECIFIED: { dimension: "G", id: "U-----" }
};

function lowerName(value) {
  return value.toLowerCase();
}

function assetName(affiliation, func, echelon, isHeadquarters) {
  const hq = isHeadquarters ? "hq" : "unit";
  return `${lowerName(affiliation)}_${lowerName(func)}_${lowerName(echelon)}_${hq}`;
}

function sidc(affiliation, func, echelon, isHeadquarters) {
  const symbol = functions[func];
  const modifier11 = isHeadquarters ? "A" : "-";
  return `S${affiliations[affiliation]}${symbol.dimension}P${symbol.id}${modifier11}${echelons[echelon]}--`;
}

fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(outputDir, { recursive: true });
ms.setStandard("APP6");

const manifest = ["asset\tanchor_u\tanchor_v\twidth\theight\tsidc"];

for (const affiliation of Object.keys(affiliations)) {
  for (const func of Object.keys(functions)) {
    for (const echelon of Object.keys(echelons)) {
      for (const isHeadquarters of [false, true]) {
        const name = assetName(affiliation, func, echelon, isHeadquarters);
        const code = sidc(affiliation, func, echelon, isHeadquarters);
        const symbol = new ms.Symbol(code, {
          size,
          standard: "APP6",
          infoFields: false,
          padding: 0
        });
        const svg = symbol.asSVG();
        const symbolSize = symbol.getSize();
        const anchor = symbol.getAnchor();
        fs.writeFileSync(path.join(outputDir, `${name}.svg`), svg);
        manifest.push([
          name,
          anchor.x / symbolSize.width,
          anchor.y / symbolSize.height,
          symbolSize.width,
          symbolSize.height,
          code
        ].join("\t"));
      }
    }
  }
}

fs.writeFileSync(path.join(outputDir, "manifest.tsv"), `${manifest.join("\n")}\n`);
console.log(`Generated ${manifest.length - 1} milsymbol SVG assets in ${outputDir}`);
