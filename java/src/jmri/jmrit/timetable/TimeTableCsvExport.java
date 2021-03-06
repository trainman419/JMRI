package jmri.jmrit.timetable;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * Export a timetable in CSV format for import into a speadsheet.
 * <pre>
 * CSV Content:
 *   Line 1 - Layout name, segment name and schedule name.
 *   Line 2 - Train names sorted by name and grouped by down or up direction.
 *   Line 3-n - Station row with the arrive and depart times for each train.
 * </pre>
 *
 * @author Dave Sand Copyright (C) 2019
 * @since 4.15.3
 */
public class TimeTableCsvExport {

    TimeTableDataManager tdm = TimeTableDataManager.getDataManager();
    boolean errorOccurred;
    FileWriter fileWriter;
    BufferedWriter bufferedWriter;
    CSVPrinter csvFile;

    HashMap<Integer, TrainEntry> trainMap = new HashMap<>();
    int trainIndex = 0;
    List<TrainEntry> downTrains = new ArrayList<>();
    List<TrainEntry> upTrains = new ArrayList<>();
    String[] stopRow;

    /**
     * Create a CSV file that can be imported into a spreadsheet to create a
     * timetable.
     *
     * @param file       The file to be created.
     * @param layoutId   The selected layout.
     * @param segmentId  The selected segment.
     * @param scheduleId The selected schedule.
     * @return true if an error occured.
     * @throws java.io.IOException if unable to export the CSV file.
     */
    public boolean exportCsv(File file, int layoutId, int segmentId, int scheduleId) throws IOException {
        // Create CSV file
        errorOccurred = false;
        fileWriter = new FileWriter(file);
        bufferedWriter = new BufferedWriter(fileWriter);
        csvFile = new CSVPrinter(bufferedWriter, CSVFormat.DEFAULT);

        // write basic data of what has been processed to file
        Layout layout = tdm.getLayout(layoutId);
        Segment segment = tdm.getSegment(segmentId);
        Schedule schedule = tdm.getSchedule(scheduleId);

        csvFile.printRecord(layout.toString(), segment.toString(), schedule.toString());

        List<String> record = new ArrayList<>();
        // this is important - it places a blank cell on the first line of the output
        record.add("");

        // get all Trains in Schedule
        List<Train> trains = tdm.getTrains(schedule.getScheduleId(), 0, true);

        // sort trains into time order by start time
        Collections.sort(trains, (o1, o2) -> Integer.compare(o1.getStartTime(), o2.getStartTime()));

        // Check direction of train (Up or Down) and add to the appropriate list
        trains.forEach((train) -> {
            // Determine train direction by checking distance variations from one stop to another
            List<Stop> trainStops = tdm.getStops(train.getTrainId(), 0, true);
            int lastStop = trainStops.size();
            if (lastStop > 1) {
                double firstDistance = tdm.getStation(trainStops.get(0).getStationId()).getDistance();
                double secondDistance = tdm.getStation(trainStops.get(1).getStationId()).getDistance();
                if (firstDistance < secondDistance) {
                    downTrains.add(new TrainEntry(train, "Down", lastStop));
                } else {
                    upTrains.add(new TrainEntry(train, "Up", lastStop));
                }
            } else {
                // One stop trains, such as yard switches are arbitrarily assigned to down
                downTrains.add(new TrainEntry(train, "Down", lastStop));
            }
        });

        // # Write reference data to trainMap for use in next processing stops
        for (TrainEntry downTrain : downTrains) {
            Train train = downTrain.getTrain();
            downTrain.setTrainIndex(trainIndex);
            trainMap.put(train.getTrainId(), downTrain);
            record.add(train.toString());
            trainIndex += 1;
        }
        for (TrainEntry upTrain : upTrains) {
            Train train = upTrain.getTrain();
            upTrain.setTrainIndex(trainIndex);
            trainMap.put(train.getTrainId(), upTrain);
            record.add(train.toString());
            trainIndex += 1;
        }
        // This is the end of the top line of the grid containing all of the train names
        csvFile.printRecord(record);

        // We have the trains - now find where they stop and record times for output
        for (Station station : tdm.getStations(segment.getSegmentId(), true)) {
            // pre-fill output values
            stopRow = new String[trainMap.size()];
            Arrays.fill(stopRow, "_");

            // Get list of all stops for this station
            tdm.getStops(0, station.getStationId(), false).forEach((stop) -> {
                Train chkTrain = tdm.getTrain(stop.getTrainId());
                // Ignore stops in other schedules
                if (!(chkTrain.getScheduleId() != schedule.getScheduleId())) {
                    // Get stored data for this train
                    TrainEntry trainEntry = trainMap.get(stop.getTrainId());
                    int idx = trainEntry.getTrainIndex();
                    int lastStop = trainEntry.getLastStation();
                    String direction = trainEntry.getDirection();
                    
                    // Collect required stop data
                    int thisStop = stop.getSeq();
                    int arrive = stop.getArriveTime();
                    int depart = stop.getDepartTime();
                    
                    if (thisStop != 1 && thisStop != lastStop) {
                        // neither first nor last stop
                        if (arrive == depart) {
                            stopRow[idx] = String.format("%s (d)", formatTime(depart));
                        } else if (direction.equals("Down")) {
                            stopRow[idx] = String.format("%s (a)%n%s (d)", formatTime(arrive), formatTime(depart));
                        } else {
                            stopRow[idx] = String.format("%s (d)%n%s (a)", formatTime(depart), formatTime(arrive));
                        }
                    } else if (thisStop == 1) {
                        // first stop (aka start)
                        stopRow[idx] = String.format("%s (d)", formatTime(depart));
                    } else if (thisStop == lastStop) {
                        // last stop
                        stopRow[idx] = String.format("%s (a)", formatTime(arrive));
                    }
                }
            });

            // end of stops, output station line
            record = new ArrayList<>();
            record.add(station.toString());
            record.addAll(Arrays.asList(stopRow));
            csvFile.printRecord(record);
        }

        // Flush the write buffer and close the file
        csvFile.flush();
        csvFile.close();

        return errorOccurred;
    }

    String formatTime(int t) {
        // Convert minutes to hh:mm
        return String.format("%02d:%02d", t / 60, t % 60);
    }

    static class TrainEntry {

        private final Train _train;
        private final String _direction;
        private final int _lastStation;
        private int _trainIndex;

        public TrainEntry(Train train, String direction, int lastStation) {
            _train = train;
            _direction = direction;
            _lastStation = lastStation;
            _trainIndex = -1;
        }

        public Train getTrain() {
            return _train;
        }

        public String getDirection() {
            return _direction;
        }

        public int getLastStation() {
            return _lastStation;
        }

        public int getTrainIndex() {
            return _trainIndex;
        }

        public void setTrainIndex(int trainIndex) {
            _trainIndex = trainIndex;
        }

        @Override
        public String toString() {
            return String.format("%s : %s : %d : %d",
                    _train.getTrainName(), _direction, _lastStation, _trainIndex);
        }
    }
}
