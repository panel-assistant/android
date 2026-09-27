// Every page served from app/src/main/assets, in PaneldServer route order. The mDNS switcher is not a
// route: it is part of the direct-view shell of every page, where the fixture roster makes it render.
import dashboard from './dashboard.mjs';
import configure from './configure.mjs';
import setup from './setup.mjs';
import profiles from './profiles.mjs';
import entities from './entities.mjs';
import install from './install.mjs';
import logs from './logs.mjs';
import api from './api.mjs';

export const PAGES = [dashboard, configure, setup, profiles, entities, install, logs, api];
