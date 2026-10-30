import json

from easymod.config import Config
from easymod.manager import ModManager
from easymod.models import AUTO, INSTALLED, MANUAL, Game

from conftest import make_plugin, make_zip, touch


def manager(tmp_path, *games):
    mgr = ModManager(Config(tmp_path / "cfg.json"))
    mgr.games = list(games)
    return mgr


def skyrim(tmp_path):
    root = tmp_path / "games" / "Skyrim Special Edition"
    touch(root / "SkyrimSE.exe")
    touch(root / "Data" / "Skyrim.esm", make_plugin())
    return Game("The Elder Scrolls V: Skyrim Special Edition", root, "skyrimse", "Steam", "489830")


def fallout4(tmp_path):
    root = tmp_path / "games" / "Fallout 4"
    touch(root / "Fallout4.exe")
    touch(root / "Data" / "Fallout4.esm", make_plugin(form_version=131))
    return Game("Fallout 4", root, "fallout4", "Steam", "377160")


def stardew(tmp_path):
    root = tmp_path / "games" / "Stardew Valley"
    touch(root / "Stardew Valley.exe")
    return Game("Stardew Valley", root, "stardew", "Steam", "413150")


def test_skyrim_mod_with_wrapper_folder_and_missing_master(tmp_path):
    sky, fo4 = skyrim(tmp_path), fallout4(tmp_path)
    mod = make_zip(tmp_path / "dl" / "Cool Armor-1234-1-0-1690000000.zip", {
        "Cool Armor/CoolArmor.esp": make_plugin(["Skyrim.esm", "SomeFramework.esm"]),
        "Cool Armor/meshes/armor/cool.nif": "nif",
        "Cool Armor/textures/armor/cool.dds": "dds",
        "Cool Armor/readme.txt": "lisez-moi",
    })
    mgr = manager(tmp_path, sky, fo4)
    analysis = mgr.analyze(mod)
    assert analysis.game == sky
    assert analysis.package.name == "Cool Armor"
    dests = {d.relative_to(sky.path).as_posix() for _, d in analysis.plan.copies}
    assert dests == {"Data/CoolArmor.esp", "Data/meshes/armor/cool.nif", "Data/textures/armor/cool.dds"}
    missing = [d for d in analysis.deps if d.status == MANUAL]
    assert [d.name for d in missing] == ["SomeFramework.esm"]
    assert "skyrimspecialedition" in missing[0].url

    mgr.install(analysis, log=lambda *_: None)
    assert (sky.path / "Data" / "meshes" / "armor" / "cool.nif").read_text() == "nif"
    plugins_txt = tmp_path / "localappdata" / "Skyrim Special Edition" / "plugins.txt"
    assert "*CoolArmor.esp" in plugins_txt.read_text()

    mgr.uninstall(mgr.installed_mods()[0]["id"])
    assert not (sky.path / "Data" / "meshes").exists()
    assert (sky.path / "Data" / "Skyrim.esm").exists()
    assert "CoolArmor" not in plugins_txt.read_text()


def test_overwritten_file_is_restored(tmp_path):
    sky = skyrim(tmp_path)
    touch(sky.path / "Data" / "textures" / "sky.dds", b"original")
    mod = make_zip(tmp_path / "dl" / "sky.zip", {"textures/sky.dds": "nouveau", "Better.esp": make_plugin()})
    mgr = manager(tmp_path, sky)
    analysis = mgr.analyze(mod)
    mgr.install(analysis, log=lambda *_: None)
    assert (sky.path / "Data" / "textures" / "sky.dds").read_text() == "nouveau"
    mgr.uninstall(mgr.installed_mods()[0]["id"])
    assert (sky.path / "Data" / "textures" / "sky.dds").read_bytes() == b"original"


def test_ba2_goes_to_fallout4_and_script_extender_is_flagged(tmp_path):
    sky, fo4 = skyrim(tmp_path), fallout4(tmp_path)
    mod = make_zip(tmp_path / "dl" / "weapon.zip", {
        "Data/Weapon.esp": make_plugin(["Fallout4.esm"], form_version=131),
        "Data/Weapon - Main.ba2": "ba2",
        "Data/F4SE/Plugins/weapon.dll": "dll",
    })
    analysis = manager(tmp_path, sky, fo4).analyze(mod)
    assert analysis.game == fo4
    assert any(d.name.startswith("F4SE") and d.status == MANUAL for d in analysis.deps)


def test_fomod_default_options(tmp_path):
    sky = skyrim(tmp_path)
    xml = """<?xml version="1.0"?><config><moduleName>X</moduleName>
    <requiredInstallFiles><folder source="Core" destination="" /></requiredInstallFiles>
    <installSteps><installStep name="s"><optionalFileGroups><group name="Res" type="SelectExactlyOne"><plugins>
      <plugin name="2K"><files><folder source="2K" destination="textures"/></files>
        <typeDescriptor><type name="Recommended"/></typeDescriptor></plugin>
      <plugin name="4K"><files><folder source="4K" destination="textures"/></files>
        <typeDescriptor><type name="Optional"/></typeDescriptor></plugin>
    </plugins></group></optionalFileGroups></installStep></installSteps></config>"""
    mod = make_zip(tmp_path / "dl" / "fomodmod.zip", {
        "fomod/ModuleConfig.xml": xml, "Core/Mod.esp": make_plugin(),
        "2K/a.dds": "2k", "4K/a.dds": "4k",
    })
    analysis = manager(tmp_path, sky).analyze(mod)
    dests = {d.relative_to(sky.path).as_posix(): s.read_text() if s.suffix == ".dds" else "" for s, d in analysis.plan.copies}
    assert dests == {"Data/Mod.esp": "", "Data/textures/a.dds": "2k"}


def test_stardew_content_pack_dependencies(tmp_path):
    game = stardew(tmp_path)
    touch(game.path / "Mods" / "ContentPatcher" / "manifest.json",
          json.dumps({"Name": "Content Patcher", "UniqueID": "Pathoschild.ContentPatcher"}).encode())
    manifest = """{
      // commentaire
      "Name": "Prettier Cows", "UniqueID": "me.cows", "Version": "1.0",
      "ContentPackFor": {"UniqueID": "Pathoschild.ContentPatcher"},
      "Dependencies": [{"UniqueID": "spacechase0.SpaceCore"}, {"UniqueID": "opt.mod", "IsRequired": false}],
    }"""
    mod = make_zip(tmp_path / "dl" / "cows.zip", {"[CP] Prettier Cows/manifest.json": manifest,
                                                   "[CP] Prettier Cows/content.json": "{}"})
    analysis = manager(tmp_path, game, skyrim(tmp_path)).analyze(mod)
    assert analysis.game == game
    assert {d.relative_to(game.path).as_posix() for _, d in analysis.plan.copies} == {
        "Mods/[CP] Prettier Cows/manifest.json", "Mods/[CP] Prettier Cows/content.json"}
    by_name = {d.name: d.status for d in analysis.deps}
    assert by_name == {"SMAPI": MANUAL, "spacechase0.SpaceCore": MANUAL}


def test_cyberpunk_layout_and_frameworks(tmp_path):
    root = tmp_path / "games" / "Cyberpunk 2077"
    touch(root / "bin" / "x64" / "Cyberpunk2077.exe")
    touch(root / "red4ext" / "RED4ext.dll")
    game = Game("Cyberpunk 2077", root, "cyberpunk")
    mod = make_zip(tmp_path / "dl" / "cp.zip", {
        "loose.archive": "a", "loose.archive.xl": "xl",
        "r6/scripts/mymod/main.reds": "reds",
        "bin/x64/plugins/cyber_engine_tweaks/mods/mymod/init.lua": "lua",
    })
    analysis = manager(tmp_path, game).analyze(mod)
    dests = {d.relative_to(root).as_posix() for _, d in analysis.plan.copies}
    assert dests == {"archive/pc/mod/loose.archive", "archive/pc/mod/loose.archive.xl",
                     "r6/scripts/mymod/main.reds", "bin/x64/plugins/cyber_engine_tweaks/mods/mymod/init.lua"}
    status = {d.name: d.status for d in analysis.deps}
    assert status == {"RED4ext": INSTALLED, "redscript": AUTO, "Cyber Engine Tweaks": AUTO, "ArchiveXL": AUTO}


def test_thunderstore_mod_for_unity_game(tmp_path):
    root = tmp_path / "games" / "Lethal Company"
    touch(root / "UnityPlayer.dll")
    game = Game("Lethal Company", root, "unity")
    manifest = json.dumps({"name": "MoreSuits", "version_number": "1.4.1", "website_url": "",
                           "dependencies": ["BepInEx-BepInExPack-5.4.2100", "Evaisa-LethalLib-0.16.0"]})
    mod = make_zip(tmp_path / "dl" / "x-MoreSuits-1.4.1.zip", {"manifest.json": manifest, "icon.png": "png",
                                                               "README.md": "#", "plugins/MoreSuits.dll": "dll"})
    analysis = manager(tmp_path, game).analyze(mod)
    assert {d.relative_to(root).as_posix() for _, d in analysis.plan.copies} == {
        "BepInEx/plugins/MoreSuits/MoreSuits.dll"}
    assert [(d.key, d.status) for d in analysis.deps] == [("ts:bepinex-bepinexpack", AUTO),
                                                          ("ts:evaisa-lethallib", AUTO)]


def test_plain_dll_needs_bepinex(tmp_path):
    root = tmp_path / "games" / "Valheim"
    touch(root / "UnityPlayer.dll")
    game = Game("Valheim", root, "unity")
    mod = make_zip(tmp_path / "dl" / "ValheimPlus.zip", {"ValheimPlus.dll": "dll"})
    analysis = manager(tmp_path, game).analyze(mod)
    assert [d.key for d in analysis.deps] == ["bepinex"]
    assert analysis.plan.copies[0][1].relative_to(root).as_posix() == "BepInEx/plugins/ValheimPlus/ValheimPlus.dll"


def test_unreal_pak(tmp_path):
    root = tmp_path / "games" / "Palworld"
    touch(root / "Pal" / "Content" / "Paks" / "Pal-Windows.pak")
    touch(root / "Engine" / "Content" / "Paks" / "x.txt")
    game = Game("Palworld", root, "unreal")
    mod = make_zip(tmp_path / "dl" / "BetterPals.zip", {"BetterPals/BetterPals_P.pak": "pak"})
    analysis = manager(tmp_path, game).analyze(mod)
    assert analysis.plan.copies[0][1].relative_to(root).as_posix() == "Pal/Content/Paks/~mods/BetterPals_P.pak"


def test_minecraft_fabric_jar(tmp_path):
    import io
    import zipfile

    mc = tmp_path / ".minecraft"
    (mc / "versions" / "fabric-loader-0.15-1.20.1").mkdir(parents=True)
    game = Game("Minecraft (Java)", mc, "minecraft")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("fabric.mod.json", json.dumps({"id": "sodium", "depends": {
            "minecraft": "~1.20.1", "fabricloader": ">=0.14", "fabric-api": "*"}}))
    jar = touch(tmp_path / "dl" / "sodium-0.5.jar", buf.getvalue())
    analysis = manager(tmp_path, game, skyrim(tmp_path)).analyze(jar)
    assert analysis.game == game
    assert analysis.plan.copies[0][1] == mc / "mods" / "sodium-0.5.jar"
    status = {d.name: d.status for d in analysis.deps}
    assert status == {"Fabric Loader": INSTALLED, "fabric-api": AUTO}


def test_generic_overlap(tmp_path):
    root = tmp_path / "games" / "Some Game"
    touch(root / "game.exe")
    touch(root / "levels" / "a.lvl")
    game = Game("Some Game", root, "generic")
    mod = make_zip(tmp_path / "dl" / "mod.zip", {"MyMod v2/levels/b.lvl": "lvl"})
    analysis = manager(tmp_path, game).analyze(mod)
    assert analysis.game == game
    assert analysis.plan.copies[0][1] == root / "levels" / "b.lvl"


def test_unknown_mod_has_no_plan(tmp_path):
    root = tmp_path / "games" / "Some Game"
    touch(root / "game.exe")
    game = Game("Some Game", root, "generic")
    mod = make_zip(tmp_path / "dl" / "stuff.zip", {"weird/thing.bin": "x"})
    mgr = manager(tmp_path, game)
    analysis = mgr.analyze(mod)
    assert analysis.plan is None
    mgr.prepare(analysis, game)
    assert analysis.plan.copies[0][1] == root / "thing.bin"


def test_thunderstore_dependencies_are_downloaded_and_installed(tmp_path, monkeypatch):
    from easymod import manager as manager_mod
    from conftest import make_zip

    root = tmp_path / "games" / "Lethal Company"
    touch(root / "UnityPlayer.dll")
    game = Game("Lethal Company", root, "unity")
    pack = make_zip(tmp_path / "srv" / "pack.zip", {
        "manifest.json": json.dumps({"name": "BepInExPack", "version_number": "5.4.2100", "dependencies": []}),
        "BepInExPack/winhttp.dll": "w", "BepInExPack/doorstop_config.ini": "d",
        "BepInExPack/BepInEx/core/BepInEx.dll": "core"})
    lib = make_zip(tmp_path / "srv" / "lib.zip", {
        "manifest.json": json.dumps({"name": "LethalLib", "version_number": "0.16.0",
                                     "dependencies": ["BepInEx-BepInExPack-5.4.2100"]}),
        "plugins/LethalLib.dll": "lib"})
    served = {"BepInEx/BepInExPack": pack, "Evaisa/LethalLib": lib}

    def fake_download(url, dest_dir, filename="", progress=None):
        key = "/".join(url.split("/download/")[1].split("/")[:2])
        return served[key]

    monkeypatch.setattr(manager_mod.net, "download", fake_download)
    mod = make_zip(tmp_path / "dl" / "MoreSuits.zip", {
        "manifest.json": json.dumps({"name": "MoreSuits", "version_number": "1.0.0",
                                     "dependencies": ["BepInEx-BepInExPack-5.4.2100", "Evaisa-LethalLib-0.16.0"]}),
        "MoreSuits.dll": "dll"})
    mgr = manager(tmp_path, game)
    analysis = mgr.analyze(mod)
    mgr.install(analysis, log=lambda *_: None)
    assert (root / "winhttp.dll").exists()
    assert (root / "BepInEx" / "core" / "BepInEx.dll").exists()
    assert (root / "BepInEx" / "plugins" / "Evaisa-LethalLib" / "LethalLib.dll").exists()
    assert (root / "BepInEx" / "plugins" / "MoreSuits" / "MoreSuits.dll").exists()
    assert len(mgr.installed_mods()) == 3

    # Un second mod qui dépend du même pack ne le retélécharge pas
    other = make_zip(tmp_path / "dl" / "Other.zip", {
        "manifest.json": json.dumps({"name": "Other", "version_number": "1.0.0",
                                     "dependencies": ["BepInEx-BepInExPack-5.4.2100", "Evaisa-LethalLib-0.16.0"]}),
        "Other.dll": "dll"})
    analysis = mgr.analyze(other)
    assert [(d.key, d.status) for d in analysis.deps] == [("bepinex", INSTALLED), ("ts:evaisa-lethallib", INSTALLED)]


def test_zip_slip_is_refused(tmp_path):
    import pytest
    from easymod.archive import ArchiveError, unpack
    from conftest import make_zip

    evil = make_zip(tmp_path / "dl" / "evil.zip", {"../../evil.txt": "pwn"})
    with pytest.raises(ArchiveError):
        unpack(evil)
