package com.realitica.parser.service;

import com.realitica.parser.entity.Stun;
import com.realitica.parser.repo.StunRepository;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class Loader {

    @Value("${realitica.url:https://www.realitica.com/en}")
    private String REALITICA_URL;

    // To select all stuns by all areas - because the cur_page has restricted - 100
    @Value("${realitica.url_with_areas_by_city:https://www.realitica.com/rentals/podgorica/Montenegro/}")
    private String REALITICA_URL_WITH_CITY_AREAS;
    @Value("${realitica.url_with_stuns_by_city_and_area:https://www.realitica.com/index.php?for=DuziNajam&lng=en&opa=Podgorica&cty%5B%5D=%s}")
    private String REALITICA_URL_WITH_STUNS_BY_CITY_AND_AREA;

    // To select all stuns by ready filres - restrictions <= 200 - because the cur_page has restricted - 100
    @Value("${realitica.url_with_stuns_filtered:}")
    private String REALITICA_URL_FILTER;

    private final SimpleDateFormat SDF = new SimpleDateFormat("dd MMM, yyyy", Locale.ENGLISH);

    @Autowired
    StunRepository stunRepository;

    @Scheduled(fixedDelay = 1000 * 60 * 60)
    public void load() {
        List<String> urlsWithStuns = !REALITICA_URL_FILTER.isEmpty() ? Arrays.asList(REALITICA_URL_FILTER) :
                loadAreas().stream()
                        .map(area -> REALITICA_URL_WITH_STUNS_BY_CITY_AND_AREA.replace("%s", area))
                        .collect(Collectors.toList());
        urlsWithStuns.forEach(urlWithStuns -> {
            log.info("Start to load by filter: {}", urlWithStuns);
            HashSet<String> ids = loadIdsBySearch(urlWithStuns);
            ids.stream().forEach(id -> saveStun(id, 1));
        });
    }


    @SneakyThrows
    private Set<String> loadAreas() {
        Set<String> areas = new LinkedHashSet<>();
        Document pageDoc = Jsoup.connect(REALITICA_URL_WITH_CITY_AREAS).get();
        Elements areasElements = pageDoc.select("#search_col2 span.geosel");
        for (Element element : areasElements) {
            String area = element.text();
            if (!StringUtils.isEmpty(area)) {
                area = area.split(" \\(")[0].trim().replace(" ", "+");
                areas.add(area);
            }
        }
        log.info("Loaded cities: {}", areas);
        return areas;
    }

    @SneakyThrows
    private HashSet loadIdsBySearch(String urlWithStuns) {
        HashSet<String> ids = new LinkedHashSet<>();

        int curPage = 0;
        while (curPage >= 0) {
            try {
                String url = urlWithStuns + "&cur_page=" + curPage;
                Document pageDoc = Jsoup.connect(url).get();
                Elements stunElements = pageDoc.select("div.thumb_div > a");
                if (stunElements.size() == 0) {
                    log.info("Last page {}", curPage + 1);
                    curPage = -1;
                    continue;
                } else {
                    log.info("Loaded page {}", curPage + 1);
                    curPage++;
                }

                List<String> listIds = stunElements.stream()
                        .map(el -> el.attr("href"))
                        .filter(link -> link.startsWith("https://www.realitica.com/en/listing/"))
                        .map(link -> link.replace("https://www.realitica.com/en/listing/", ""))
                        .collect(Collectors.toList());
                ids.addAll(listIds);
            } catch (Throwable th) {
                log.error("Can't load page with stuns, goes to sleep", th);
                Thread.sleep(1000);
            }
        }
        return ids;
    }

    /**
     * attributesMap = {LinkedHashMap@7973}  size = 16
     * "Type" -> "Apartment Long Term Rental"
     * "District" -> "Podgorica"
     * "Location" -> "Masline"
     * "Address" -> "Masline, Podgorica, Crna Gora"
     * "Price" -> "€450"
     * "Bedrooms" -> "2"
     * "Living Area" -> "90 m"
     * "Description" -> "For rent a two bedroom apartment in house in Masline, area of 90m2, air conditioned.The yard is fenced and cultivated.Monthly rental price is € 450"
     * "More info at" -> ""
     * "Listed by" -> ""
     * "Registration number" -> "www.freshestate.me - facebook.com/FreshEstateMontenegro/"
     * "Mobile" -> "Mobitel Izdavanje Podgorica Alisa +382 69 355 898; Prodaja kuca i placeva Pg,Dg Zoran +382 69 274 699, sjever CG Darko +382 69 120 052, Primorje Miloš +382 69 022 070, Vladimir +382 69 355 886, office +382 69 223 514 - www.freshestate.me - www.Freshestate.me - We're multi language speaking stuff, contact us on @, Viber, WhatsApp, Facebook, Instagram and visit our offices and our site."
     * "Phone" -> "Telefon Prodaja stanova Podgorica Darko +382 69 120 052, Mirko +382 67 260 336; Aleksandar +382 69 372 006; Vladimir +382 69 372 007; Marijana + 382 69 372 009; Nikola +382 69 372 066; Tamara +382 69 372 116 ; Primorje Miloš +382 67 207 047, Vlado +382 67 260 391 ; office +382 69 223 514 - www.freshestate.me - We are multi language speaking stuff - Eng+382 67 207 047 - Tur+382 69 355 898 - Rus+382 69 355 886 - contact us on @, Viber, WhatsApp, Facebook, Instagram and visit our offices and our site."
     * "Listing ID" -> "2238224 (15098)"
     * "Last Modified" -> "6 Oct, 2020"
     * "Tags" -> ""
     *
     * @param id
     * @return
     * @throws IOException
     */
    @SneakyThrows
    public Map<String, String> loadStunAttributes(String id, int repeats) {
        if (repeats < 0) {
            return null;
        }

        try {
            log.info("Loading stun {}", id);

            Document doc = Jsoup.connect(REALITICA_URL + "/listing/" + id).get();
            Map<String, String> attributesMap = new LinkedHashMap();

            Elements parentElements = doc.select("div");
            for (Element parentElement : parentElements) {
                List<Node> nodes = parentElement.childNodes();
                for (int i = 0; i < nodes.size(); i++) {
                    Node node = nodes.get(i);
                    if (node instanceof Element) {
                        Element el = (Element) node;
                        if (Tag.valueOf("strong").equals(el.tag()) && i < nodes.size() - 1 && nodes.get(i + 1).toString().startsWith(": ")) {
                            attributesMap.put(el.text(), nodes.get(i + 1).toString().replace(":", "").trim());
                        }
                    }
                }
            }
            return attributesMap;
        } catch (Throwable th) {
            log.error("Can't load stun {}", id, th);
            Thread.sleep(1000);
            return loadStunAttributes(id, repeats - 1);
        }
    }

    private void saveStun(String id, int repeats) {
        try {
            if (repeats < 0) {
                return;
            }

            Map<String, String> attributesMap = loadStunAttributes(id, 1);
            if (attributesMap == null) {
                return;
            }

            Date lastMobified = null;
            try {
                lastMobified = attributesMap.get("Last Modified") != null ? SDF.parse(attributesMap.get("Last Modified")) : null;
            } catch (ParseException e) {
                log.error("can't parse data {}, {}", id, attributesMap.get("Last Modified"));
            }

            Stun stun = stunRepository.findByRealiticaId(id);
            if (stun == null) {
                stun = new Stun();
                stun.setRealiticaId(id);
            }
            stun.setType(attributesMap.get("Type"));
            stun.setDistrict(attributesMap.get("District"));
            stun.setLocation(attributesMap.get("Location"));
            stun.setAddress(attributesMap.get("Address"));
            stun.setPrice(attributesMap.get("Price") != null ? attributesMap.get("Price").replace("€", "") : null);
            stun.setBedrooms(attributesMap.get("Bedrooms"));
            stun.setLivingArea(attributesMap.get("Living Area"));
            stun.setMoreInfo(attributesMap.get("More info at"));
            stun.setLastModified(lastMobified);
            stun.setType(attributesMap.get("Type"));
            stun.setLink(REALITICA_URL + "/listing/" + id);
            stunRepository.save(stun);
            log.info("Save stun {}", id);
        } catch (Throwable th) {
            log.error("Can't save stun {}", id);
        }
    }
}
