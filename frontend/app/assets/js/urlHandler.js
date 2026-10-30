import {config, saveConfig} from './config.js';
import {log} from './log.js';

const isURLValid = url => {
    try { 
      return Boolean(new URL(url)); 
    }
    catch(e)
    { 
      return false; 
    }
  }

export const EksiAccess = Object.freeze({
  OK: "ok",
  // Cloudflare is putting this connection through a challenge. A tab solves it;
  // a request from the extension's background cannot.
  CHALLENGED: "challenged",
  UNREACHABLE: "unreachable",
});

// The challenge answers 403 (or 503) with cf-mitigated: challenge. The body is
// the fallback in case a browser hides the header from the extension.
export async function isCloudflareChallenge(response)
{
  if (response.status !== 403 && response.status !== 503) return false;
  if (response.headers.get("cf-mitigated") === "challenge") return true;
  try {
    return (await response.clone().text()).includes("/cdn-cgi/challenge-platform/");
  } catch (err) {
    return false;
  }
}

async function accessOf(url)
{
  try 
  {
    let response = await fetch(url);
    if (await isCloudflareChallenge(response)) return EksiAccess.CHALLENGED;
    // redirected response is not considered correct
    // TODO: redirected response probably means the user is using a VPN. So, redirected url could be used instead 
    return response.status === 200 && !response.redirected ? EksiAccess.OK : EksiAccess.UNREACHABLE;
  } 
  catch (err) 
  {
    return EksiAccess.UNREACHABLE;
  }
}

// eksisozluk.com is banned by government and cannot be accessible anymore
// this function finds the latest URL and saves it to config
// return: an EksiAccess value
export async function handleEksiSozlukURL()
{
  let access = await accessOf(config.EksiSozlukURL);
  log.info("url", "EksiSozluk access: " + access + " at " + config.EksiSozlukURL);

  // Ekşi answered, only with a challenge: it has not moved, so looking for a new
  // address would not help, and calling it unreachable would be wrong.
  if(access === EksiAccess.CHALLENGED)
    return access;

  if(access !== EksiAccess.OK)
  {
    log.warn("url", "EksiSozluk is not accessible at: " + config.EksiSozlukURL);

    try 
    {
      // use a API to find last location of eksisozluk.com 
      let response = await fetch(config.whereIsEksiSozlukURL);
      if(response.status !== 200)
        throw "Where is EksiSozluk API is not accessible";

      let newEksiSozlukURL = await response.text();
      log.info("url", "Obtained EksiSozluk URL: " + newEksiSozlukURL);

      if(config.EksiSozlukURL == newEksiSozlukURL)
        throw "Obtained EksiSozluk URL is the same with the one in the config";

      if(!isURLValid(newEksiSozlukURL))
        throw "Obtained EksiSozluk URL is not valid";

      let newAccess = await accessOf(newEksiSozlukURL);

      if(newAccess === EksiAccess.OK)
      {
        log.info("url", "new EksiSozluk URL accessible, saved into config");
        config.EksiSozlukURL = newEksiSozlukURL;
        await saveConfig(config);
        return EksiAccess.OK;
      }
      else 
      {
        throw "Obtained EksiSozluk URL is not accessible";
      }
    } 
    catch (err) 
    {
      log.err("url", err);
      return EksiAccess.UNREACHABLE;
    }
  } 

  return EksiAccess.OK;
}