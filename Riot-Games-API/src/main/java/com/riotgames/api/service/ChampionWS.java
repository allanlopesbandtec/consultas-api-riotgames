package com.riotgames.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riotgames.api.client.RiotgamesClient;
import com.riotgames.api.model.Dto.ChampionDto;
import com.riotgames.api.model.Dto.ChampionMasteryDto;
import com.riotgames.api.model.Summoner;
import com.riotgames.api.model.champion.Champion;
import com.riotgames.api.model.champion.ChampionByMastery;
import com.riotgames.api.model.champion.ChampionDetail;
import com.riotgames.api.model.error.ApiError;
import com.riotgames.api.utils.UtilsWS;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChampionWS {

    @Autowired
    private RiotgamesClient riotgamesClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SummonerWS summonerWS;

    protected Map<String, Champion> mapChampions() throws ApiError {
        Map<String, Champion> mapChampions = new HashMap<>();
        String request = "";

        try {
            /** @apiNote Requisição com campeões da página web ->
             *                          {@link {http://ddragon.leagueoflegends.com/cdn/12.5.1/data/pt_BR/champion.json}}
             */
            request = riotgamesClient.findChampions();
            //Mapeamento da resposta, dentro da chave "data" com o JSONObject vamos encontrar os campeões
            String campeoes = new JSONObject(request).getJSONObject("data").toString();
            //ObjectMapper + contrutor customizado vai retornar campões mapeados!
            mapChampions = objectMapper.readValue(campeoes, Champion.class).getchampionMap();
        } catch (ApiError ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "mapChampions", "Error to map champions", ex.getLocalizedMessage());
        }

        return mapChampions;
    }

    public ChampionDetail getChampDetail(String championName) throws ApiError {
        ChampionDetail championDetail = null;
        String request;


        if (championName == null || championName.isBlank() || championName.isEmpty()) {
            throw new ApiError(ChampionWS.class, "getChampDetail", "Champion name is empty or blank", HttpStatus.BAD_REQUEST);
        }

        try {
            request = riotgamesClient.findChampion(championName);
            String championResponse = new JSONObject(request).getJSONObject("data").getJSONObject(championName).toString();
            championDetail = objectMapper.readValue(championResponse, ChampionDetail.class);

            UtilsWS.saveRequest(championDetail.getName(), championDetail.getTitle());
        } catch (ApiError ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "getChampDetail", "Error to find champion", ex.getLocalizedMessage());
        }

        return championDetail;
    }

    protected List<ChampionByMastery> getMasteryBySummoner(String encryptedSummonerId) throws ApiError {
        ChampionByMastery[] championMastery;
        String request;

        try {
            request = riotgamesClient.getChampionsByMastery(encryptedSummonerId);
            championMastery = objectMapper.readValue(request, ChampionByMastery[].class);
        } catch (ApiError ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "championMasteryBySummoner", "Error to list championsByMastery", ex.getLocalizedMessage());
        }

        return new ArrayList<>(Arrays.asList(championMastery));
    }

    public List<ChampionMasteryDto> getChampionsByMastery(String nick) throws ApiError {
        //Buscando invocador
        Summoner summoner = summonerWS.findSummoner(nick);

        //Recuperando maestrias por invocador
        List<ChampionByMastery> championsByMasteries = getMasteryBySummoner(summoner.getId());

        //Todos os champs
        List<ChampionDto> championDto = getCampeaoDtos();

        //Lista que vamos retornar Api
        return getChampionMasteryDto(championsByMasteries, championDto);
    }

    public List<ChampionDto> getCampeaoDtos() throws ApiError {
        return StaticWS.championsList.stream().map(ChampionDto::new).collect(Collectors.toList());
    }

    //Match entre campeoes com e sem maestria
    public List<ChampionMasteryDto> getChampionMasteryDto(List<ChampionByMastery> masteryList, List<ChampionDto> dtoList) throws ApiError {

        List<ChampionMasteryDto> championsMasteryDtos = new ArrayList<>();
        try {
            //1 for para maestrias do jogador e segundo para Campeao
            for (ChampionByMastery championByMastery : masteryList) {
                String chmapionId = championByMastery.getChampionId().toString();

                if (!dtoList.isEmpty()) {
                    for (ChampionDto champDto : dtoList) {
                        if (champDto.getKey().equals(chmapionId)) {
                            ChampionMasteryDto championMasteryDto = new ChampionMasteryDto(championByMastery, champDto);
                            championsMasteryDtos.add(championMasteryDto);
                        }
                    }
                } else {
                    throw new ApiError(ChampionWS.class, "championByMastery", "Mastery list is empty", HttpStatus.NO_CONTENT);
                }
            }

            return championsMasteryDtos;

        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "championByMastery", "Error to merge champions with mastery ranks", ex.getLocalizedMessage());
        }
    }

    public List<ChampionMasteryDto> getFilterSearchChampions(String nick, String lane) throws ApiError {
        List<ChampionMasteryDto> masteryDtos = listChampionsMostPlayed(nick, lane);
        List<ChampionMasteryDto> filterMasteryList = new ArrayList<>();

        try {

            for (ChampionMasteryDto championMasteryDto : masteryDtos) {

                if (!championMasteryDto.getChestWinned()) {
                    filterMasteryList.add(championMasteryDto);
                }

            }
        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "getCustomSearchChampions", "Error to filter champs by lane", ex.getLocalizedMessage());
        }

        //Ajustar validação
        if (filterMasteryList.isEmpty()) {
            throw new ApiError(ChampionWS.class, "getFilterSearchChampions", "Mastery list is empty, check de lane name", HttpStatus.BAD_REQUEST);
        }

        return filterMasteryList;
    }


    /**
     * @param lane lane escolhida pelo jogador
     * @return {@link List<ChampionDto>}
     * @throws ApiError classe de exceção RiotGamesClient
     */
    public Map<String, Double> mapChampionsMostPlayed(String lane) throws ApiError {
        String request = riotgamesClient.getChampionsMostPlayed();

        String laneData = request.split("a.exports=")[1];

        laneData = laneData.replace("},function(){}]);", "");

        Map<String, Double> championsMap = new HashMap<>();

        JSONObject jsonObject = new JSONObject(laneData);

        try {
            Map<String, String> auxMap = objectMapper.readValue(jsonObject.get(lane).toString(), HashMap.class);

            for (Map.Entry<String, String> champion : auxMap.entrySet()) {
                championsMap.put(champion.getKey(), Double.parseDouble(champion.getValue()));
            }

            auxMap.clear();
        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "mapChampionsMostPlayed", "Error to map champions by lane", ex.getLocalizedMessage());
        }

        return championsMap;
    }

    public List<ChampionMasteryDto> listChampionsMostPlayed(String nick, String lane) throws ApiError {
        List<ChampionMasteryDto> championMasteryDtoListAux = getChampionsByMastery(nick);
        Map<String, Double> mapChampions = mapChampionsMostPlayed(lane);
        List<ChampionMasteryDto> championMasteryDtoList = new ArrayList<>();

        try {
            for (Map.Entry<String, Double> map : mapChampions.entrySet()) {
                for (ChampionMasteryDto championMastery : championMasteryDtoListAux) {
                    if (championMastery.getKey().toString().equals(map.getKey())) {
                        championMastery.setPickRate(map.getValue() * 1000);
                        championMasteryDtoList.add(championMastery);
                    }
                }
            }

            championMasteryDtoListAux.clear();

        } catch (Exception ex) {
            throw new ApiError(ChampionWS.class, "listChampionsMostPlayed", "Error to list champions by lane", ex.getLocalizedMessage());
        }

        return championMasteryDtoList;
    }

}
