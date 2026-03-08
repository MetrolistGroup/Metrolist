#!/usr/bin/env python3
"""Validate shared translation placeholders and CLDR cardinal plural categories."""
import argparse
import json
from pathlib import Path
import re
import sys
from xml.etree import ElementTree


# Category facts from Unicode CLDR 48, including languages not yet bundled.
# https://github.com/unicode-org/cldr-json/blob/48.0.0/cldr-json/cldr-core/supplemental/plurals.json
CATEGORY_GROUPS = {
    'one few many other': (
        'be cs lt pl ru sk uk'
    ),
    'one few other': (
        'bs hr mo ro sh shi sr'
    ),
    'one many other': (
        'ca es fr it lld pt pt-PT scn vec'
    ),
    'one other': (
        'af ak am an as asa ast az bal bem bez bg bho bn brx ce ceb cgg chr ckb csw da de doi dv ee el en eo '
        'et eu fa ff fi fil fo fur fy gl gsw gu guw ha haw hi hu hy ia ie io is jgo jmc ka kab kaj kcg kk kkj '
        'kl kn kok kok-Latn ks ksb ku ky lb lg lij ln mas mg mgo mk ml mn mr nah nb nd ne nl nn nnh no nr nso '
        'ny nyn om or os pa pap pcm ps rm rof rwk saq sc sd sdh seh si sn so sq ss ssy st sv sw syr ta te teo '
        'ti tig tk tl tn tr ts tzm ug ur uz ve vo vun wa wae xh xog yi zu'
    ),
    'one two few many other': (
        'br ga gv mt sgs'
    ),
    'one two few other': (
        'dsb gd hsb sl'
    ),
    'one two other': (
        'he iu naq sat se sma smi smj smn sms'
    ),
    'other': (
        'bm bo dz hnj id ig ii ja jbo jv jw kde kea km ko lkt lo ms my nqo osa sah ses sg su th to tpi und vi '
        'wo yo yue zh'
    ),
    'zero one other': (
        'blo cv ksh lag lv prg'
    ),
    'zero one two few many other': (
        'ar ars cy kw'
    ),
}

# These categories identify exactly one integer, so the count may be spelled out.
FIXED_INTEGER_CATEGORIES = {
    'one': (
        'af an ar ars asa ast az bal bem bez bg blo brx ca ce cgg chr ckb cs cv cy da de dv ee el en eo es et '
        'eu fi fo fur fy ga gl gsw ha haw he hu ia ie io it iu jgo jmc ka kaj kcg kk kkj kl ks ksb ksh ku kw '
        'ky lag lb lg lij lld mas mgo ml mn mo mr mt nah naq nb nd ne nl nn nnh no nr ny nyn om or os pap pl '
        'ps pt-PT rm ro rof rwk saq sat sc scn sd sdh se seh sk sma smi smj smn sms sn so sq ss ssy st sv sw '
        'syr ta te teo tig tk tn tr ts ug ur uz ve vec vo vun wae xh xog yi'
    ).split(),
    'two': (
        'ar ars cy ga he iu mt naq sat se sgs sma smi smj smn sms'
    ).split(),
    'zero': (
        'ar ars blo cv cy ksh kw lag'
    ).split(),
}

CATEGORIES = {
    locale: set(categories.split())
    for categories, locales in CATEGORY_GROUPS.items()
    for locale in locales.split()
}
FORMAT = re.compile(r"%(?:(\d+)\$([-#+ 0,(<]*\d*(?:\.\d+)?)|([-#+0,(<]*\d*(?:\.\d+)?))([a-zA-Z])")
# Plurals normally format their selection count first. These callers pass it second.
COUNT_ARGUMENT = {"download_notification_progress": 2, "queued_songs": 2, "import_playlists_notice": 2}


def locale_for_directory(directory):
    if directory == "values":
        return None
    qualifier = directory.removeprefix("values-")
    parts = qualifier.removeprefix("b+").split("+") if qualifier.startswith("b+") else qualifier.split("-")
    language = {"in": "id", "iw": "he"}.get(parts[0], parts[0])
    region = next((part.removeprefix("r") for part in parts[1:]
                   if part.removeprefix("r").isupper()), None)
    regional = f"{language}-{region}"
    return regional if regional in CATEGORIES else language


def placeholders(element):
    result = {}
    text = "".join(element.itertext())
    matches = list(FORMAT.finditer(text))
    starts = {match.start() for match in matches}
    for marker in re.finditer(r"%\d+\$", text):
        if marker.start() not in starts:
            raise ValueError(f"incomplete placeholder at {marker[0]!r}")
    for match in matches:
        index, indexed_modifiers, unindexed_modifiers, kind = match.groups()
        modifiers = indexed_modifiers or unindexed_modifiers
        if index is None or int(index) < 1:
            raise ValueError(f"placeholder {match[0]!r} needs a positive argument index")
        if modifiers or kind not in {"s", "d"}:
            raise ValueError(f"unsupported Compose placeholder {match[0]!r}")
        index = int(index)
        if index in result and result[index] != kind:
            raise ValueError(f"argument {index} has conflicting placeholder types")
        result[index] = kind
    return result


def read_catalog(path):
    result = {}
    for element in ElementTree.parse(path).getroot():
        name = element.get("name")
        if name in result:
            raise ValueError(f"duplicate resource {name}")
        result[name] = element
    return result


def catalog_errors(catalog, base, directory):
    locale = locale_for_directory(directory)
    if locale is not None and locale not in CATEGORIES:
        yield f"unknown CLDR locale {locale}; add its category data before bundling it"
        return
    for name, element in catalog.items():
        reference = base.get(name)
        if reference is None:
            yield f"{name}: resource is absent from the base catalog"
            continue
        if name.startswith("crash_") or name == "database_downgrade":
            if reference.get("translatable") != "false":
                yield f"{name}: crash recovery resources must set translatable=\"false\" in the base catalog"
            if locale is not None:
                yield f"{name}: crash recovery resources must not appear in locale catalogs"
                continue
        if element.tag != reference.tag:
            yield f"{name}: expected {reference.tag}, found {element.tag}"
            continue
        if element.tag == "plurals":
            quantities = [item.get("quantity") for item in element]
            required = CATEGORIES[locale] if locale else {"one", "other"}
            missing = required - set(quantities)
            if missing:
                yield f"{name}: missing plural categories: {', '.join(sorted(missing))}"
            if len(quantities) != len(set(quantities)):
                yield f"{name}: duplicate plural categories"
            if set(quantities) - {"zero", "one", "two", "few", "many", "other"}:
                yield f"{name}: invalid plural category"
            samples = list(element)
        elif element.tag == "string":
            samples = [element]
        else:
            continue
        for item in samples:
            quantity = item.get("quantity")
            label = f"{name}[{quantity}]" if quantity else name
            if quantity:
                ref = reference.find("item[@quantity='other']") if locale else reference.find(f"item[@quantity='{quantity}']")
                if ref is None:
                    ref = reference.find("item[@quantity='other']")
                if ref is None:
                    yield f"{label}: base plural has no other item"
                    continue
            else:
                ref = reference
            try:
                expected = placeholders(ref)
                actual = placeholders(item)
            except ValueError as error:
                yield f"{label}: {error}"
                continue
            # A translated single/dual form may spell out its selection count.
            optional_count = locale in FIXED_INTEGER_CATEGORIES.get(quantity, [])
            missing = expected.keys() - actual.keys()
            if optional_count:
                missing -= {COUNT_ARGUMENT.get(name, 1)}
            wrong = {index for index in actual if actual[index] != expected.get(index)}
            if missing or wrong:
                yield f"{label}: placeholder mismatch: expected {expected}, found {actual}"


def translation_errors(resources):
    base_path = resources / "values/strings.xml"
    base = read_catalog(base_path)
    for path in sorted(resources.glob("values*/strings.xml")):
        try:
            catalog = read_catalog(path)
            yield from (f"{path}: {error}" for error in catalog_errors(catalog, base, path.parent.name))
        except (ValueError, ElementTree.ParseError) as error:
            yield f"{path}: {error}"


def missing_translations(resources):
    base = read_catalog(resources / "values/strings.xml")
    translatable = {name for name, element in base.items() if element.get("translatable") != "false"}
    return {
        path.parent.name: sorted(translatable - read_catalog(path).keys())
        for path in sorted(resources.glob("values-*/strings.xml"))
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--missing-report", type=Path, help="Write missing resource keys per locale as JSON")
    args = parser.parse_args()
    resources = Path(__file__).resolve().parents[1] / "shared/src/commonMain/composeResources"
    try:
        errors = list(translation_errors(resources))
        if args.missing_report:
            args.missing_report.write_text(json.dumps(missing_translations(resources), indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, ElementTree.ParseError) as error:
        errors = [str(error)]
    for error in errors:
        print(error, file=sys.stderr)
    sys.exit(bool(errors))
