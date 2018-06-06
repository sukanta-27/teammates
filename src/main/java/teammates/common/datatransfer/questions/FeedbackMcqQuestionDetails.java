package teammates.common.datatransfer.questions;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import teammates.common.datatransfer.FeedbackParticipantType;
import teammates.common.datatransfer.FeedbackSessionResultsBundle;
import teammates.common.datatransfer.TeamDetailsBundle;
import teammates.common.datatransfer.attributes.FeedbackQuestionAttributes;
import teammates.common.datatransfer.attributes.FeedbackResponseAttributes;
import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.HttpRequestHelper;
import teammates.common.util.Logger;
import teammates.common.util.SanitizationHelper;
import teammates.common.util.Templates;
import teammates.common.util.Templates.FeedbackQuestion.FormTemplates;
import teammates.common.util.Templates.FeedbackQuestion.Slots;
import teammates.logic.core.CoursesLogic;
import teammates.logic.core.InstructorsLogic;
import teammates.logic.core.StudentsLogic;
import teammates.ui.template.InstructorFeedbackResultsResponseRow;

public class FeedbackMcqQuestionDetails extends FeedbackQuestionDetails {
    private static final Logger log = Logger.getLogger();

    private boolean hasAssignedWeights;
    private List<Double> mcqWeights;
    private double mcqOtherWeight;
    private int numOfMcqChoices;
    private List<String> mcqChoices;
    private boolean otherEnabled;
    private FeedbackParticipantType generateOptionsFor;
    private StudentAttributes studentDoingQuestion;

    public FeedbackMcqQuestionDetails() {
        super(FeedbackQuestionType.MCQ);

        this.hasAssignedWeights = false;
        this.mcqWeights = new ArrayList<>();
        this.numOfMcqChoices = 0;
        this.mcqChoices = new ArrayList<>();
        this.otherEnabled = false;
        this.mcqOtherWeight = 0;
        this.generateOptionsFor = FeedbackParticipantType.NONE;
    }

    @Override
    public List<String> getInstructions() {
        return null;
    }

    public int getNumOfMcqChoices() {
        return numOfMcqChoices;
    }

    public List<String> getMcqChoices() {
        return mcqChoices;
    }

    public boolean hasAssignedWeights() {
        return hasAssignedWeights;
    }

    public List<Double> getMcqWeights() {
        return new ArrayList<>(mcqWeights);
    }

    public double getMcqOtherWeight() {
        return mcqOtherWeight;
    }

    public FeedbackParticipantType getGenerateOptionsFor() {
        return generateOptionsFor;
    }

    @Override
    public boolean extractQuestionDetails(
            Map<String, String[]> requestParameters,
            FeedbackQuestionType questionType) {

        int numOfMcqChoices = 0;
        List<String> mcqChoices = new LinkedList<>();
        boolean mcqOtherEnabled = false; // TODO change this when implementing "other, please specify" field

        if ("on".equals(HttpRequestHelper.getValueFromParamMap(
                                    requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_MCQOTHEROPTIONFLAG))) {
            mcqOtherEnabled = true;
        }

        String generatedMcqOptions =
                HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                       Const.ParamsNames.FEEDBACK_QUESTION_MCQ_GENERATED_OPTIONS);

        if (generatedMcqOptions.equals(FeedbackParticipantType.NONE.toString())) {
            String numMcqChoicesCreatedString =
                    HttpRequestHelper.getValueFromParamMap(requestParameters,
                                                           Const.ParamsNames.FEEDBACK_QUESTION_NUMBEROFCHOICECREATED);
            Assumption.assertNotNull("Null number of choice for MCQ", numMcqChoicesCreatedString);
            int numMcqChoicesCreated = Integer.parseInt(numMcqChoicesCreatedString);

            for (int i = 0; i < numMcqChoicesCreated; i++) {
                String paramName = Const.ParamsNames.FEEDBACK_QUESTION_MCQCHOICE + "-" + i;
                String mcqChoice = HttpRequestHelper.getValueFromParamMap(requestParameters, paramName);
                if (mcqChoice != null && !mcqChoice.trim().isEmpty()) {
                    mcqChoices.add(mcqChoice);
                    numOfMcqChoices++;
                }
            }

            String hasAssignedWeightsString = HttpRequestHelper.getValueFromParamMap(
                    requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_WEIGHTS_ASSIGNED);
            boolean hasAssignedWeights = "on".equals(hasAssignedWeightsString);
            List<Double> mcqWeights = getMcqWeights(
                    requestParameters, numMcqChoicesCreated, hasAssignedWeights);
            double mcqOtherWeight = getMcqOtherWeight(requestParameters, mcqOtherEnabled, hasAssignedWeights, mcqWeights);
            setMcqQuestionDetails(
                    numOfMcqChoices, mcqChoices, mcqOtherEnabled, hasAssignedWeights, mcqWeights, mcqOtherWeight);
        } else {
            setMcqQuestionDetails(FeedbackParticipantType.valueOf(generatedMcqOptions));
        }
        return true;
    }

    private List<Double> getMcqWeights(Map<String, String[]> requestParameters,
            int numMcqChoicesCreated, boolean hasAssignedWeights) {
        List<Double> mcqWeights = new ArrayList<>();

        if (!hasAssignedWeights) {
            return mcqWeights;
        }

        for (int i = 0; i < numMcqChoicesCreated; i++) {
            String choice = HttpRequestHelper.getValueFromParamMap(
                    requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_MCQCHOICE + "-" + i);
            String weight = HttpRequestHelper.getValueFromParamMap(
                    requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_WEIGHT + "-" + i);

            if (choice != null && !choice.trim().isEmpty() && weight != null) {
                try {
                    // Do not add weight to mcqWeights if the weight cannot be parsed
                    mcqWeights.add(Double.parseDouble(weight));
                } catch (NumberFormatException e) {
                    log.warning("Failed to parse weight for MCQ question: " + weight);
                }
            }
        }

        return mcqWeights;
    }

    private double getMcqOtherWeight(Map<String, String[]> requestParameters,
            boolean mcqOtherEnabled, boolean hasAssignedWeights, List<Double> mcqWeights) {

        double mcqOtherWeight = 0;

        if (!hasAssignedWeights || !mcqOtherEnabled) {
            return mcqOtherWeight;
        }

        // If no other option has weights attached,
        // the other option should not have a weight attached either
        if (mcqWeights.isEmpty()) {
            return mcqOtherWeight;
        }

        String weightOther = HttpRequestHelper.getValueFromParamMap(
                requestParameters, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_OTHER_WEIGHT);
        Assumption.assertNotNull("Null 'other' weight of MCQ question", weightOther);
        try {
            // Do not assign value to mcqOtherWeight if the weight can not be parsed.
            mcqOtherWeight = Double.parseDouble(weightOther);
        } catch (NumberFormatException e) {
            log.warning("Failed to parse \"other\" weight of MCQ question: " + weightOther);
        }
        return mcqOtherWeight;
    }

    private void setMcqQuestionDetails(int numOfMcqChoices, List<String> mcqChoices, boolean otherEnabled,
            boolean hasAssignedWeights, List<Double> mcqWeights, double mcqOtherWeight) {
        this.numOfMcqChoices = numOfMcqChoices;
        this.mcqChoices = mcqChoices;
        this.otherEnabled = otherEnabled;
        this.hasAssignedWeights = hasAssignedWeights;
        this.mcqWeights = mcqWeights;
        this.mcqOtherWeight = mcqOtherWeight;
        this.generateOptionsFor = FeedbackParticipantType.NONE;
    }

    private void setMcqQuestionDetails(FeedbackParticipantType generateOptionsFor) {
        this.numOfMcqChoices = 0;
        this.mcqChoices = new ArrayList<>();
        this.otherEnabled = false;
        this.generateOptionsFor = generateOptionsFor;
        Assumption.assertTrue(
                "Can only generate students, students (excluding self), teams, teams (excluding self) or instructors",
                generateOptionsFor == FeedbackParticipantType.STUDENTS
                || generateOptionsFor == FeedbackParticipantType.STUDENTS_EXCLUDING_SELF
                || generateOptionsFor == FeedbackParticipantType.TEAMS
                || generateOptionsFor == FeedbackParticipantType.TEAMS_EXCLUDING_SELF
                || generateOptionsFor == FeedbackParticipantType.INSTRUCTORS);
    }

    @Override
    public String getQuestionTypeDisplayName() {
        return Const.FeedbackQuestionTypeNames.MCQ;
    }

    public boolean getOtherEnabled() {
        return otherEnabled;
    }

    @Override
    public boolean shouldChangesRequireResponseDeletion(FeedbackQuestionDetails newDetails) {
        FeedbackMcqQuestionDetails newMcqDetails = (FeedbackMcqQuestionDetails) newDetails;

        if (this.numOfMcqChoices != newMcqDetails.numOfMcqChoices
                || !this.mcqChoices.containsAll(newMcqDetails.mcqChoices)
                || !newMcqDetails.mcqChoices.containsAll(this.mcqChoices)) {
            return true;
        }

        if (this.generateOptionsFor != newMcqDetails.generateOptionsFor) {
            return true;
        }

        return this.otherEnabled != newMcqDetails.otherEnabled;
    }

    @Override
    public String getQuestionWithExistingResponseSubmissionFormHtml(boolean sessionIsOpen, int qnIdx,
            int responseIdx, String courseId, int totalNumRecipients, FeedbackResponseDetails existingResponseDetails,
            StudentAttributes student) {
        studentDoingQuestion = student;
        FeedbackMcqResponseDetails existingMcqResponse = (FeedbackMcqResponseDetails) existingResponseDetails;
        List<String> choices = generateOptionList(courseId);

        StringBuilder optionListHtml = new StringBuilder();
        String optionFragmentTemplate = FormTemplates.MCQ_SUBMISSION_FORM_OPTIONFRAGMENT;
        Boolean isOtherSelected = existingMcqResponse.isOtherOptionAnswer();

        for (int i = 0; i < choices.size(); i++) {
            String optionFragment =
                    Templates.populateTemplate(optionFragmentTemplate,
                            Slots.QUESTION_INDEX, Integer.toString(qnIdx),
                            Slots.RESPONSE_INDEX, Integer.toString(responseIdx),
                            Slots.DISABLED, sessionIsOpen ? "" : "disabled",
                            Slots.CHECKED,
                                    existingMcqResponse.getAnswerString().equals(choices.get(i)) ? "checked" : "",
                            Slots.FEEDBACK_RESPONSE_TEXT, Const.ParamsNames.FEEDBACK_RESPONSE_TEXT,
                            Slots.MCQ_CHOICE_VALUE, SanitizationHelper.sanitizeForHtml(choices.get(i)));
            optionListHtml.append(optionFragment).append(System.lineSeparator());
        }
        if (otherEnabled) {
            String otherOptionFragmentTemplate = FormTemplates.MCQ_SUBMISSION_FORM_OTHEROPTIONFRAGMENT;
            String otherOptionFragment =
                    Templates.populateTemplate(otherOptionFragmentTemplate,
                            Slots.QUESTION_INDEX, Integer.toString(qnIdx),
                            Slots.RESPONSE_INDEX, Integer.toString(responseIdx),
                            Slots.DISABLED, sessionIsOpen ? "" : "disabled",
                            Slots.TEXT_DISABLED, sessionIsOpen && isOtherSelected ? "" : "disabled",
                            Slots.CHECKED, isOtherSelected ? "checked" : "",
                            Slots.FEEDBACK_RESPONSE_TEXT, Const.ParamsNames.FEEDBACK_RESPONSE_TEXT,
                            Slots.MCQ_PARAM_IS_OTHER_OPTION_ANSWER,
                                    Const.ParamsNames.FEEDBACK_QUESTION_MCQ_ISOTHEROPTIONANSWER,
                            Slots.MCQ_CHOICE_VALUE,
                                    SanitizationHelper.sanitizeForHtml(existingMcqResponse.getOtherFieldContent()),
                            Slots.MCQ_OTHER_OPTION_ANSWER, isOtherSelected ? "1" : "0");
            optionListHtml.append(otherOptionFragment).append(System.lineSeparator());
        }
        return Templates.populateTemplate(
                FormTemplates.MCQ_SUBMISSION_FORM,
                Slots.MCQ_SUBMISSION_FORM_OPTION_FRAGMENTS, optionListHtml.toString());
    }

    @Override
    public String getQuestionWithoutExistingResponseSubmissionFormHtml(
            boolean sessionIsOpen, int qnIdx, int responseIdx, String courseId, int totalNumRecipients,
            StudentAttributes student) {
        studentDoingQuestion = student;
        List<String> choices = generateOptionList(courseId);

        StringBuilder optionListHtml = new StringBuilder();
        String optionFragmentTemplate = FormTemplates.MCQ_SUBMISSION_FORM_OPTIONFRAGMENT;

        for (int i = 0; i < choices.size(); i++) {
            String optionFragment =
                    Templates.populateTemplate(optionFragmentTemplate,
                            Slots.QUESTION_INDEX, Integer.toString(qnIdx),
                            Slots.RESPONSE_INDEX, Integer.toString(responseIdx),
                            Slots.DISABLED, sessionIsOpen ? "" : "disabled",
                            Slots.CHECKED, "",
                            Slots.FEEDBACK_RESPONSE_TEXT, Const.ParamsNames.FEEDBACK_RESPONSE_TEXT,
                            Slots.MCQ_CHOICE_VALUE, SanitizationHelper.sanitizeForHtml(choices.get(i)));
            optionListHtml.append(optionFragment).append(System.lineSeparator());
        }

        if (otherEnabled) {
            String otherOptionFragmentTemplate = FormTemplates.MCQ_SUBMISSION_FORM_OTHEROPTIONFRAGMENT;
            String otherOptionFragment =
                       Templates.populateTemplate(otherOptionFragmentTemplate,
                            Slots.QUESTION_INDEX, Integer.toString(qnIdx),
                            Slots.RESPONSE_INDEX, Integer.toString(responseIdx),
                            Slots.DISABLED, sessionIsOpen ? "" : "disabled",
                            Slots.TEXT_DISABLED, "disabled",
                            Slots.CHECKED, "",
                            Slots.FEEDBACK_RESPONSE_TEXT, Const.ParamsNames.FEEDBACK_RESPONSE_TEXT,
                            Slots.MCQ_PARAM_IS_OTHER_OPTION_ANSWER,
                                    Const.ParamsNames.FEEDBACK_QUESTION_MCQ_ISOTHEROPTIONANSWER,
                            Slots.MCQ_CHOICE_VALUE, "",
                            Slots.MCQ_OTHER_OPTION_ANSWER, "0");
            optionListHtml.append(otherOptionFragment).append(System.lineSeparator());
        }

        return Templates.populateTemplate(
                FormTemplates.MCQ_SUBMISSION_FORM,
                Slots.MCQ_SUBMISSION_FORM_OPTION_FRAGMENTS, optionListHtml.toString());
    }

    private List<String> generateOptionList(String courseId) {
        List<String> optionList = new ArrayList<>();

        switch (generateOptionsFor) {
        case NONE:
            optionList = mcqChoices;
            break;
        case STUDENTS:
            //fallthrough
        case STUDENTS_EXCLUDING_SELF:
            List<StudentAttributes> studentList = StudentsLogic.inst().getStudentsForCourse(courseId);

            if (generateOptionsFor == FeedbackParticipantType.STUDENTS_EXCLUDING_SELF) {
                studentList.removeIf(studentInList -> studentInList.email.equals(studentDoingQuestion.email));
            }

            for (StudentAttributes student : studentList) {
                optionList.add(student.name + " (" + student.team + ")");
            }

            optionList.sort(null);
            break;
        case TEAMS:
            //fallthrough
        case TEAMS_EXCLUDING_SELF:
            try {
                List<TeamDetailsBundle> teamList = CoursesLogic.inst().getTeamsForCourse(courseId);

                if (generateOptionsFor == FeedbackParticipantType.TEAMS_EXCLUDING_SELF) {
                    teamList.removeIf(teamInList -> teamInList.name.equals(studentDoingQuestion.team));
                }

                for (TeamDetailsBundle team : teamList) {
                    optionList.add(team.name);
                }

                optionList.sort(null);
            } catch (EntityDoesNotExistException e) {
                Assumption.fail("Course disappeared");
            }
            break;
        case INSTRUCTORS:
            List<InstructorAttributes> instructorList =
                    InstructorsLogic.inst().getInstructorsForCourse(courseId);

            for (InstructorAttributes instructor : instructorList) {
                optionList.add(instructor.name);
            }

            optionList.sort(null);
            break;
        default:
            Assumption.fail("Trying to generate options for neither students, teams nor instructors");
            break;
        }

        return optionList;
    }

    @Override
    public String getQuestionSpecificEditFormHtml(int questionNumber) {
        StringBuilder optionListHtml = new StringBuilder();
        String optionFragmentTemplate = FormTemplates.MCQ_EDIT_FORM_OPTIONFRAGMENT;
        DecimalFormat weightFormat = new DecimalFormat("#.##");

        // Create MCQ options
        for (int i = 0; i < numOfMcqChoices; i++) {
            String optionFragment =
                    Templates.populateTemplate(optionFragmentTemplate,
                            Slots.ITERATOR, Integer.toString(i),
                            Slots.MCQ_CHOICE_VALUE, SanitizationHelper.sanitizeForHtml(mcqChoices.get(i)),
                            Slots.MCQ_PARAM_CHOICE, Const.ParamsNames.FEEDBACK_QUESTION_MCQCHOICE);

            optionListHtml.append(optionFragment).append(System.lineSeparator());
        }

        // Create MCQ weights
        StringBuilder weightFragmentHtml = new StringBuilder();
        String weightFragmentTemplate = FormTemplates.MCQ_EDIT_FORM_WEIGHTFRAGMENT;
        for (int i = 0; i < numOfMcqChoices; i++) {
            String weightFragment =
                    Templates.populateTemplate(weightFragmentTemplate,
                            Slots.ITERATOR, Integer.toString(i),
                            Slots.MCQ_WEIGHT, hasAssignedWeights ? weightFormat.format(mcqWeights.get(i)) : "0",
                            Slots.MCQ_PARAM_WEIGHT, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_WEIGHT);
            weightFragmentHtml.append(weightFragment).append(System.lineSeparator());
        }

        return Templates.populateTemplate(
                FormTemplates.MCQ_EDIT_FORM,
                Slots.MCQ_EDIT_FORM_OPTION_FRAGMENTS, optionListHtml.toString(),
                Slots.QUESTION_NUMBER, Integer.toString(questionNumber),
                Slots.NUMBER_OF_CHOICE_CREATED, Const.ParamsNames.FEEDBACK_QUESTION_NUMBEROFCHOICECREATED,
                Slots.MCQ_NUM_OF_MCQ_CHOICES, Integer.toString(numOfMcqChoices),
                Slots.CHECKED_OTHER_OPTION_ENABLED, otherEnabled ? "checked" : "",
                Slots.MCQ_PARAM_OTHER_OPTION, Const.ParamsNames.FEEDBACK_QUESTION_MCQOTHEROPTION,
                Slots.MCQ_PARAM_OTHER_OPTION_FLAG, Const.ParamsNames.FEEDBACK_QUESTION_MCQOTHEROPTIONFLAG,
                Slots.MCQ_CHECKED_GENERATED_OPTION, generateOptionsFor == FeedbackParticipantType.NONE ? "" : "checked",
                Slots.MCQ_GENERATED_OPTIONS, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_GENERATED_OPTIONS,
                Slots.GENERATE_OPTIONS_FOR_VALUE, generateOptionsFor.toString(),
                Slots.STUDENT_SELECTED, generateOptionsFor == FeedbackParticipantType.STUDENTS ? "selected" : "",
                Slots.STUDENTS_TO_STRING, FeedbackParticipantType.STUDENTS.toString(),
                Slots.STUDENT_EXCLUDING_SELF_SELECTED,
                    generateOptionsFor == FeedbackParticipantType.STUDENTS_EXCLUDING_SELF ? "selected" : "",
                Slots.STUDENTS_EXCLUDING_SELF_TO_STRING, FeedbackParticipantType.STUDENTS_EXCLUDING_SELF.toString(),
                Slots.TEAM_SELECTED, generateOptionsFor == FeedbackParticipantType.TEAMS ? "selected" : "",
                Slots.TEAMS_TO_STRING, FeedbackParticipantType.TEAMS.toString(),
                Slots.TEAM_EXCLUDING_SELF_SELECTED,
                    generateOptionsFor == FeedbackParticipantType.TEAMS_EXCLUDING_SELF ? "selected" : "",
                Slots.TEAMS_EXCLUDING_SELF_TO_STRING, FeedbackParticipantType.TEAMS_EXCLUDING_SELF.toString(),
                Slots.INSTRUCTOR_SELECTED, generateOptionsFor == FeedbackParticipantType.INSTRUCTORS ? "selected" : "",
                Slots.INSTRUCTORS_TO_STRING, FeedbackParticipantType.INSTRUCTORS.toString(),
                Slots.MCQ_TOOLTIPS_ASSIGN_WEIGHT, Const.Tooltips.FEEDBACK_QUESTION_MCQ_ASSIGN_WEIGHTS,
                Slots.MCQ_PARAM_ASSIGN_WEIGHT, Const.ParamsNames.FEEDBACK_QUESTION_MCQ_WEIGHTS_ASSIGNED,
                Slots.MCQ_EDIT_FORM_WEIGHT_FRAGMENTS, weightFragmentHtml.toString(),
                Slots.MCQ_CHECK_ASSIGN_WEIGHT, hasAssignedWeights ? "checked" : "");
    }

    @Override
    public String getNewQuestionSpecificEditFormHtml() {
        // Add two empty options by default
        numOfMcqChoices = 2;
        mcqChoices.add("");
        mcqChoices.add("");
        hasAssignedWeights = false;

        return "<div id=\"mcqForm\">"
                  + getQuestionSpecificEditFormHtml(-1)
             + "</div>";
    }

    @Override
    public String getQuestionAdditionalInfoHtml(int questionNumber, String additionalInfoId) {
        StringBuilder optionListHtml = new StringBuilder(200);
        String optionFragmentTemplate = FormTemplates.MCQ_ADDITIONAL_INFO_FRAGMENT;

        if (generateOptionsFor != FeedbackParticipantType.NONE) {
            String optionHelpText = String.format(
                    "<br>The options for this question is automatically generated from the list of all %s in this course.",
                    generateOptionsFor.toString().toLowerCase());
            optionListHtml.append(optionHelpText);
        }

        if (numOfMcqChoices > 0) {
            optionListHtml.append("<ul style=\"list-style-type: disc;margin-left: 20px;\" >");
            for (int i = 0; i < numOfMcqChoices; i++) {
                String optionFragment =
                        Templates.populateTemplate(optionFragmentTemplate,
                                Slots.MCQ_CHOICE_VALUE, SanitizationHelper.sanitizeForHtml(mcqChoices.get(i)));

                optionListHtml.append(optionFragment);
            }
        }
        if (otherEnabled) {
            String optionFragment =
                    Templates.populateTemplate(optionFragmentTemplate, Slots.MCQ_CHOICE_VALUE, "Others");
            optionListHtml.append(optionFragment);
        }
        optionListHtml.append("</ul>");

        String additionalInfo = Templates.populateTemplate(
                FormTemplates.MCQ_ADDITIONAL_INFO,
                Slots.QUESTION_TYPE_NAME, this.getQuestionTypeDisplayName(),
                Slots.MCQ_ADDITIONAL_INFO_FRAGMENTS, optionListHtml.toString());

        return Templates.populateTemplate(
                FormTemplates.FEEDBACK_QUESTION_ADDITIONAL_INFO,
                Slots.MORE, "[more]",
                Slots.LESS, "[less]",
                Slots.QUESTION_NUMBER, Integer.toString(questionNumber),
                Slots.ADDITIONAL_INFO_ID, additionalInfoId,
                Slots.QUESTION_ADDITIONAL_INFO, additionalInfo);
    }

    @Override
    public String getQuestionResultStatisticsHtml(List<FeedbackResponseAttributes> responses,
            FeedbackQuestionAttributes question,
            String studentEmail,
            FeedbackSessionResultsBundle bundle,
            String view) {

        if ("student".equals(view) || responses.isEmpty()) {
            return "";
        }

        StringBuilder fragments = new StringBuilder();
        Map<String, Integer> answerFrequency = collateAnswerFrequency(responses);

        DecimalFormat df = new DecimalFormat("#.##");

        answerFrequency.forEach((key, value) ->
                fragments.append(Templates.populateTemplate(FormTemplates.MCQ_RESULT_STATS_OPTIONFRAGMENT,
                        Slots.MCQ_CHOICE_VALUE, SanitizationHelper.sanitizeForHtml(key),
                        Slots.COUNT, value.toString(),
                        Slots.PERCENTAGE, df.format(100 * (double) value / responses.size()))));

        return Templates.populateTemplate(FormTemplates.MCQ_RESULT_STATS, Slots.FRAGMENTS, fragments.toString());
    }

    @Override
    public String getQuestionResultStatisticsCsv(
            List<FeedbackResponseAttributes> responses,
            FeedbackQuestionAttributes question,
            FeedbackSessionResultsBundle bundle) {
        if (responses.isEmpty()) {
            return "";
        }

        StringBuilder fragments = new StringBuilder();
        Map<String, Integer> answerFrequency = collateAnswerFrequency(responses);

        DecimalFormat df = new DecimalFormat("#.##");

        answerFrequency.forEach((key, value) -> fragments.append(SanitizationHelper.sanitizeForCsv(key)).append(',')
                     .append(value.toString()).append(',')
                     .append(df.format(100 * (double) value / responses.size())).append(System.lineSeparator()));

        return "Choice, Response Count, Percentage" + System.lineSeparator()
               + fragments.toString();
    }

    @Override
    public String getCsvHeader() {
        return "Feedback";
    }

    @Override
    public String getQuestionTypeChoiceOption() {
        return "<li data-questiontype = \"MCQ\"><a href=\"javascript:;\"> "
               + Const.FeedbackQuestionTypeNames.MCQ + "</a></li>";
    }

    @Override
    public List<String> validateQuestionDetails(String courseId) {
        List<String> errors = new ArrayList<>();
        if (generateOptionsFor == FeedbackParticipantType.NONE) {

            if (numOfMcqChoices < Const.FeedbackQuestion.MCQ_MIN_NUM_OF_CHOICES) {
                errors.add(Const.FeedbackQuestion.MCQ_ERROR_NOT_ENOUGH_CHOICES
                        + Const.FeedbackQuestion.MCQ_MIN_NUM_OF_CHOICES + ".");
            }

            // If weights are enabled, number of choices and weights should be same.
            // In case if a user enters an invalid weight for a valid choice,
            // the mcqChoices.size() will be greater than mcqWeights.size(), which will
            // trigger this error message.
            if (hasAssignedWeights && mcqChoices.size() != mcqWeights.size()) {
                errors.add(Const.FeedbackQuestion.MCQ_ERROR_INVALID_WEIGHT);
            }

            // If weights are not enabled, but weight list is not empty or otherWeight is not 0
            // In that case, this error will be triggered.
            if (!hasAssignedWeights && (!mcqWeights.isEmpty() || mcqOtherWeight != 0)) {
                errors.add(Const.FeedbackQuestion.MCQ_ERROR_INVALID_WEIGHT);
            }

            // If weight is enabled, but other option is disabled, and mcqOtherWeight is not 0
            // In that case, this error will be triggered.
            if (hasAssignedWeights && !otherEnabled && mcqOtherWeight != 0) {
                errors.add(Const.FeedbackQuestion.MCQ_ERROR_INVALID_WEIGHT);
            }
        }

        //TODO: check that mcq options do not repeat. needed?

        return errors;
    }

    @Override
    public List<String> validateResponseAttributes(
            List<FeedbackResponseAttributes> responses,
            int numRecipients) {
        List<String> errors = new ArrayList<>();

        for (FeedbackResponseAttributes response : responses) {
            FeedbackMcqResponseDetails frd = (FeedbackMcqResponseDetails) response.getResponseDetails();

            if (!otherEnabled && generateOptionsFor == FeedbackParticipantType.NONE
                    && !mcqChoices.contains(frd.getAnswerString())) {
                errors.add(frd.getAnswerString() + Const.FeedbackQuestion.MCQ_ERROR_INVALID_OPTION);
            }
        }
        return errors;
    }

    @Override
    public Comparator<InstructorFeedbackResultsResponseRow> getResponseRowsSortOrder() {
        return null;
    }

    @Override
    public String validateGiverRecipientVisibility(FeedbackQuestionAttributes feedbackQuestionAttributes) {
        return "";
    }

    private Map<String, Integer> collateAnswerFrequency(List<FeedbackResponseAttributes> responses) {
        Map<String, Integer> answerFrequency = new LinkedHashMap<>();

        for (String option : mcqChoices) {
            answerFrequency.put(option, 0);
        }

        if (otherEnabled) {
            answerFrequency.put("Other", 0);
        }

        for (FeedbackResponseAttributes response : responses) {
            FeedbackResponseDetails responseDetails = response.getResponseDetails();
            boolean isOtherOptionAnswer =
                    ((FeedbackMcqResponseDetails) responseDetails).isOtherOptionAnswer();
            String key = isOtherOptionAnswer ? "Other" : responseDetails.getAnswerString();

            answerFrequency.put(key, answerFrequency.getOrDefault(key, 0) + 1);
        }

        return answerFrequency;
    }
}
