"""Registre des profils de jeux. Pour ajouter un jeu, créez un module et ajoutez-le ici."""

from . import bethesda, bg3, cyberpunk, generic, minecraft, stardew, unity, unreal

# Ordre = priorité lors de la reconnaissance d'un dossier de jeu (les plus spécifiques d'abord).
ALL = (bethesda.PROFILES + stardew.PROFILES + cyberpunk.PROFILES + bg3.PROFILES + minecraft.PROFILES
       + unity.PROFILES + unreal.PROFILES + generic.PROFILES)
BY_KEY = {p.key: p for p in ALL}
APPID_TO_KEY = {appid: p.key for p in bethesda.PROFILES for appid in p.appids}
APPID_TO_KEY.update({"413150": "stardew", "1091500": "cyberpunk", "1086940": "bg3"})


def get(key: str):
    return BY_KEY.get(key, BY_KEY["generic"])


def profile_for_game(path, appid: str = "") -> str:
    if appid in APPID_TO_KEY:
        return APPID_TO_KEY[appid]
    for profile in ALL:
        try:
            if profile.owns_game(path):
                return profile.key
        except OSError:
            continue
    return "generic"
