from unicodedata import name
from unittest import result
from urllib import response
import requests
import os
from datetime import datetime, timedelta


def check_auth(f):
    def decorate(self, *args, **kwargs):
        i = 0
        error = None
        while i < 3:
            try:
                result = f(self, *args, **kwargs)
                return result
            except requests.exceptions.HTTPError as e:
                error = e
                orc.auth(self)
                i = i + 1
        raise Exception(error)

    return decorate


class orc:
    __username = ""
    __password = ""
    __token = ""
    token_path = ""
    orc_url = ""

    def __init__(self, orc_url: str, orc_username: str, orc_password: str):
        self.orc_url = orc_url
        self.token_path = os.getenv("userprofile") + "\\token.txt"
        self.__username = orc_username
        self.__password = orc_password
        self.__token = self.__read_token()
        if self.__ping_orc():
            self.__token = self.auth()
            self.__update_token()

    def __read_token(self) -> str:
        if not os.path.isfile(self.token_path):
            token_file = open(self.token_path, "x")
            token_file.write("1")
            token_file.close()
        return open(self.token_path, "r").read()

    def __update_token(self):
        if not os.path.isfile(self.token_path):
            token_file = open(self.token_path, "x")
            token_file.close()
        open(self.token_path, "w").write(self.__token)

    def auth(self):
        data = {"userName": self.__username, "password": self.__password}
        api_method = "api/Account"
        result = requests.post(self.orc_url + api_method, json=data, verify=True)
        res_json = result.json()
        return res_json["token"]

    def __ping_orc(self):
        api_method = "api/Assets"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        return result.status_code == 401

    @check_auth
    def get_asset(self, asset_name: str) -> str | tuple[str, str]:
        """
        Получить ассет

        Параметры:
            - asset_name*: [String] Наименование ассета.
        """
        api_method = "api/Assets/GetByName?name=" + asset_name
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        if result.status_code == 404:
            raise Exception(
                "Ассет по пути:{} не был найден".format(self.orc_url + api_method)
            )

        result.raise_for_status()
        res_json = result.json()

        match res_json["valueType"]:
            case 1:
                return res_json["strValue"]
            case 2:
                return res_json["intVlue"]
            case 3:
                return res_json["floatValue"]
            case 4:
                return res_json["boolValue"]
            case 5:
                return res_json["dateTimeValue"]
            case 6:
                return res_json["loginValue"], res_json["passwordValue"]
            case _:
                return "asset type not found"

    @check_auth
    def update_asset(self, asset_name: str, value_type: int, value) -> None:
        """
        Обновить ассет напрямую через PUT.

        Параметры:
            - asset_name*: [String] Наименование ассета.
            - value_type*: 1-String, 2-Int, 3-Float, 4-Bool, 5-DateTime
            - value*: значение ассета
        """
        api_method = f"api/Assets/v2/SetGlobalByName?name={asset_name}"
        headers = {"Authorization": f"Bearer {self.__token}"}

        # Словарь имен полей в зависимости от типа
        type_fields = {
            1: "strValue",
            2: "intValue",
            3: "floatValue",
            4: "boolValue",
            5: "dateTimeValue",
        }

        if value_type not in type_fields:
            raise ValueError(f"Неподдерживаемый тип ассета: {value_type}")

        payload = {"valueType": value_type, type_fields[value_type]: value}

        response = requests.put(
            self.orc_url + api_method, json=payload, verify=False, headers=headers
        )

        response.raise_for_status()

    @check_auth
    def get_transaction_data(self, queue_name: str) -> response:
        robotName = "Jenkins"
        api_method = (
            "api/ExchangeQueues/peek/{}?robotName={}&metadata=true&retray=3".format(
                queue_name, robotName
            )
        )
        result = requests.put(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        return result

    @check_auth
    def get_transaction_data_by_id(
        self, queue_name: str, transaction_reference: str, transaction_id: str
    ) -> dict:
        api_method = "api/ExchangeQueues/v2/{}/Items?NaturalKey={}".format(
            self.__get_queueid_by_name(queue_name), transaction_reference
        )
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        for transaction in result.json()["result"]:
            if transaction["id"] == transaction_id:
                return transaction

    @check_auth
    def __get_queueid_by_name(self, queue_name: str) -> str:
        api_method = "api/ExchangeQueues"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        for queue in result.json():
            if queue["name"] == queue_name:
                return queue["id"]

    @check_auth
    def add_transaction(
        self,
        queue_name: str,
        value: str,
        metadata: dict | None = "",
        reference: str | None = "",
        postpone: str | None = "",
    ) -> bool:
        """
        Добавить транзакцию в очередь

        Параметры:
            - queue_name*: [String] Название очереди
            - value*: [String] Значение транзакции
            - metadate: [Dict] Метаданные транзакции
            - reference: [String] Референс транзакции
            - postpone: [Time] Отложить транзакцию до указаного времени (Формат времени: `'%Y-%m-%dT%H:%M:%S'`)
        """
        api_method = "api/ExchangeQueues/{}/Items/Add".format(
            self.__get_queueid_by_name(queue_name)
        )
        transaction_object = {
            "value": value,
            "naturalKey": reference,
            "metadata": metadata,
            "postponeAt": postpone,
        }
        result = requests.put(
            self.orc_url + api_method,
            verify=True,
            json=transaction_object,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        return result.status_code == 200

    @check_auth
    def set_transaction_status(
        self,
        queue_name: str,
        transaction_id: str,
        status: str,
        status_text: str | None = "",
    ) -> response:
        """
        Изменить статус транзакции

        Параметры:
            - queue_name*: [String] Название очереди
            - transaction_id*: [String] ID транзакции
            - status*: [String] Статус транзакции (`success|system|business`)
            - status_text: [String] Текст статуса
        """
        robotName = "Jenkins"
        statuses = {"success": 0, "system": 1, "business": 2}
        status = statuses[status]
        data = {"type": status, "text": status_text}
        api_method = "api/ExchangeQueues/changestatus/{}/{}".format(
            queue_name, transaction_id
        )
        result = requests.put(
            self.orc_url + api_method,
            json=data,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        return result

    @check_auth
    def get_trans_by_filter(
        self,
        queue_name: str,
        page_size: int,
        page_number: int,
        status: int | None = None,
    ):
        api_method = "api/ExchangeQueues/v2/{}/Items?pageNumber={}&pageSize={}".format(
            self.__get_queueid_by_name(queue_name), page_number, page_size
        )
        if status:
            api_method += "&EventTypes={}".format(status)
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        return result

    @check_auth
    def get_project_queue(self):
        """
        Получить очередь проектов
        """
        api_method = "api/RpaProjectQueue"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def get_robots(self):
        """
        Получить данные о роботах
        """
        api_method = "api/Robots"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def get_assignments(self):
        """
        Получить список задач
        """
        api_method = "api/Assignments/v2"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def delete_project_in_queue(self, projectId: str):
        """
        Удалить проект из очереди

        Параметры:
            - projectId*: [String] ID процесса
        """
        api_method = f"api/RpaProjectQueue/{projectId}"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def get_launch_history_for_started_from(
        self, projectId: str, startedAt: datetime | None = datetime.today()
    ):
        """
        Получить историю запусков для проекта

        Параметры:
            - projectId*: [String] ID процесса
            - startedAt: [DateTime] дата и время, начиная с которых надо взять выборку
        """
        api_method = (
            f"api/RpaProjectLaunches/project/{projectId}?StartedAt1={startedAt}"
        )
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def get_trans_by_id(
        self, queue_name: str, number_of_transactions: int, reference: str, status: int
    ):
        """
        Получение транзакций по референсу

        Параметры:
            - queue_name: [String] Название очереди
            - number_of_transactions: [int] кол-во транзакций
            - reference: [String] Референс для поиска транзакций
            - status: [String] Статус транзакции (`1-success|2-error|3-business|4-inprogress|5-new`)
        """
        api_method = f"api/ExchangeQueues/v2/{self.__get_queueid_by_name(queue_name)}/Items?pageSize={number_of_transactions}&NaturalKey={reference}&EventTypes={status}&NotRemoved=true"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        return res_json

    @check_auth
    def get_last_trans_id(
        self, queue_name: str, number_of_transactions: int, reference: str
    ):
        """
        Получение последней транзакции по референсу на основе даты создания

        Параметры:
            - queue_name: [String] Название очереди
            - number_of_transactions: [int] кол-во транзакций
            - reference: [String] Референс для поиска транзакций
        """
        max_time = datetime.now() + timedelta(days=-30)
        last_trans_id = -1
        api_method = f"api/ExchangeQueues/v2/{self.__get_queueid_by_name(queue_name)}/Items?pageSize={number_of_transactions}&NaturalKey={reference}&EventTypes[]=1&EventTypes[]=2&EventTypes[]=3&NotRemoved=true"
        result = requests.get(
            self.orc_url + api_method,
            verify=True,
            headers={"Authorization": "Bearer " + self.__token},
        )
        result.raise_for_status()
        res_json = result.json()
        for trans in res_json["result"]:
            createdAt = datetime.strptime(trans["createdAt"], "%Y-%m-%dT%H:%M:%S.%f")
            if createdAt > max_time:
                max_time = createdAt
                last_trans_id = trans["id"]
        if last_trans_id == -1:
            raise Exception("Транзакции устарели на 30 дней")
        return last_trans_id
